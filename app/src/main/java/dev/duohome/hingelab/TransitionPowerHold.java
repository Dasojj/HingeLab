package dev.duohome.hingelab;

import android.os.*;
import java.lang.reflect.*;

/** Short, server-timed power override. Never changes persistent settings. */
public final class TransitionPowerHold implements AutoCloseable {
    private final Object service;
    private final Method set;
    private final IBinder[] tokens={new Binder(),new Binder()};
    private final boolean[] touched=new boolean[2];
    public static final int HOLD_MS=700;
    public TransitionPowerHold() throws Exception {
        if(android.os.Process.myUid()!=2000 || !"SM-F971B".equals(Build.MODEL)) throw new SecurityException("Only tested shell/device mapping supported");
        Class<?> api=Class.forName("android.hardware.display.IDisplayManager");
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"display");
        service=Class.forName(api.getName()+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        set=api.getMethod("setDisplayStateOverrideWithDisplayId",IBinder.class,int.class,int.class,int.class);
    }
    private void hold(int id) throws Exception { touched[id]=true; set.invoke(service,tokens[id],2,id,HOLD_MS); }
    public void pulse() throws Exception {
        // Logical IDs exchange physical panels during handoff. Both bounded tokens
        // cover the exchange; neither token is renewed in a background loop.
        try { hold(0); hold(1); }
        catch(Exception e) { close(); throw e; }
    }
    @Override public void close() throws Exception {
        Exception failure=null;
        for(int id=0;id<2;id++) if(touched[id]) try { set.invoke(service,tokens[id],0,id,-1); touched[id]=false; }
        catch(Exception e) { failure=e; }
        if(failure!=null) throw failure;
    }
    private static boolean ownsLock(String dump,int token) {
        return dump.contains("lock:"+token+",");
    }
    /** Holds the already active inner panel only; checks system-side expiration. */
    public static void main(String[] args) throws Exception {
        System.out.println("POWER_CHECK_BEGIN");
        String initial=DualDisplaySession.command("dumpsys","device_state");
        if(!initial.contains("mOverrideState=Optional.empty") || !initial.contains("mBaseState=Optional[DeviceState{identifier=3,")) throw new IllegalStateException("Normal open phone required");
        try(TransitionPowerHold h=new TransitionPowerHold()) {
            String before=DualDisplaySession.command("sh","-c","dumpsys display | grep -A 5 'Display State Override Locks:'");
            if(!before.contains("Display State Override Locks: size=0")) throw new IllegalStateException("Existing power overrides; skip smoke");
            h.hold(0);
            String held=DualDisplaySession.command("sh","-c","dumpsys display | grep -A 5 'Display State Override Locks:'");
            java.util.regex.Matcher match=java.util.regex.Pattern.compile("Display State Override Locks: size=1\\s+0: +0 ON \\(lock:(-?\\d+),").matcher(held);
            boolean found=match.find();
            int token=found ? Integer.parseInt(match.group(1)) : 0;
            System.out.println("POWER_HOLD_ACCEPTED="+found+" ttl_ms="+HOLD_MS);
            Thread.sleep(HOLD_MS+300);
            boolean expired=!ownsLock(DualDisplaySession.command("sh","-c","dumpsys display | grep -A 5 'Display State Override Locks:'"),token);
            System.out.println("POWER_HOLD_AUTO_EXPIRED="+expired);
            if(!found || !expired) throw new IllegalStateException("Timed override not verified");
        }
    }
}
