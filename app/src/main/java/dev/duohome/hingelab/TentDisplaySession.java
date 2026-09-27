package dev.duohome.hingelab;

import java.io.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Bounded experiment: physical posture starts one concurrent session per opening. */
public final class TentDisplaySession {
    private static final Pattern BASE=Pattern.compile("mBaseState=Optional\\[DeviceState\\{identifier=(\\d+)");
    private boolean requested, lock0, lock1;
    private final boolean coverFirst;
    private final int mode;
    private TentDisplaySession(boolean coverFirst) { this.coverFirst=coverFirst; this.mode=coverFirst?5:4; }
    private static void event(String s) { System.out.println("DISPLAY "+s); System.out.flush(); }
    private static String cmd(String... a) throws Exception { return DualDisplaySession.command(a); }
    private boolean releaseLock(int display) {
        try { cmd("cmd","power","set-wakelock","release","-d",Integer.toString(display),"FULL_WAKE_LOCK"); return true; }
        catch(Exception error) {
            try {
                String locks=cmd("cmd","power","set-wakelock","list");
                String prefix="Display "+display+", wakelock type: FULL_WAKE_LOCK:";
                for(String line:locks.split("\\R")) {
                    if(line.contains(prefix) && line.contains("held=false")) {
                        event("cleanup_verified display="+display+" lock already released; "+error.getMessage());
                        return true;
                    }
                }
            } catch(Exception verification) { event("cleanup_error verification display="+display+" "+verification); }
            event("cleanup_error release display="+display+" "+error); return false;
        }
    }
    private void release() {
        // In cover-first mode keep the cover awake while resetting the layout.
        // At CLOSED the same physical cover keeps logical ID 0 throughout.
        if(coverFirst && requested) {
            try {
                cmd("cmd","device_state","state","reset"); requested=false;
                for(int i=0;i<10;i++) {
                    String state=cmd("dumpsys","device_state");
                    if(state.contains("mPendingState=Optional.empty") && state.contains("mOverrideState=Optional.empty")) break;
                    Thread.sleep(50);
                }
            } catch(Exception e) { event("cleanup_error "+e); }
        }
        if(lock1 && releaseLock(1)) lock1=false;
        if(lock0 && releaseLock(0)) lock0=false;
        if(requested) { try { cmd("cmd","device_state","state","reset"); requested=false; } catch(Exception e) { event("cleanup_error "+e); } }
    }
    public static void main(String[] args) throws Exception { new TentDisplaySession(args.length>0 && args[0].equals("coverFirst")).run(); }
    private void run() throws Exception {
        if(android.os.Process.myUid()!=2000) throw new SecurityException("shell required");
        CountDownLatch stop=new CountDownLatch(1);
        Thread reader=new Thread(() -> { try { while(System.in.read()!=-1) {} } catch(IOException ignored) {} finally { stop.countDown(); } });
        reader.setDaemon(true); reader.start();
        long deadline=android.os.SystemClock.elapsedRealtime()+60000;
        boolean active=false, armed=true, sawClosed=!coverFirst;
        int previous=-1;
        try {
            String initial=cmd("dumpsys","device_state");
            if(!initial.contains("identifier="+mode+", name='"+(coverFirst?"CONCURRENT_OUTER_DEFAULT":"CONCURRENT_INNER_DEFAULT")+"'") || !initial.contains("mOverrideState=Optional.empty")) throw new IOException("Unsupported state or existing override");
            if(cmd("cmd","power","set-wakelock","list").contains("held=true")) throw new IOException("Existing shell wakelock");
            event("waiting "+(coverFirst?"Сложи телефон: внешний останется главным":"CLOSED → TENT")+"; 60 seconds");
            while(android.os.SystemClock.elapsedRealtime()<deadline && !stop.await(100,TimeUnit.MILLISECONDS)) {
                String state=cmd("dumpsys","device_state");
                Matcher m=BASE.matcher(state);
                if(!m.find()) throw new IOException("Physical base state unavailable");
                int base=Integer.parseInt(m.group(1));
                if(base!=previous) { event("posture "+base); previous=base; }
                if(base==0) {
                    sawClosed=true;
                    if(active) { if(!coverFirst) event("idle"); release(); active=false; if(coverFirst) event("idle"); }
                    armed=true;
                    continue;
                }
                if(base>3) throw new IOException("Unknown physical posture "+base);
                if(active && state.contains("mOverrideState=Optional.empty")) {
                    // Never power-reset in a loop when Samsung cancels an override.
                    event("idle"); release(); active=false; armed=false;
                    event("waiting Override cancelled; close and reopen to retry");
                }
                if(active || !armed || !sawClosed || stop.getCount()==0) continue;
                armed=false;
                event("activating posture="+base);
                requested=true;
                lock0=true; cmd("cmd","power","set-wakelock","acquire","-d","0","FULL_WAKE_LOCK");
                cmd("cmd","device_state","state",Integer.toString(mode));
                boolean committed=false;
                for(int i=0;i<10 && stop.getCount()!=0;i++) {
                    String check=cmd("dumpsys","device_state");
                    if(check.contains("mBaseState=Optional[DeviceState{identifier=0,")) break;
                    if(check.contains("mCommittedState=Optional[DeviceState{identifier="+mode+",")) { committed=true; break; }
                    Thread.sleep(80);
                }
                if(!committed) { release(); event("waiting Opening interrupted; waiting for next cycle"); continue; }
                cmd("cmd","display","enable-display","1");
                if(!coverFirst) cmd("cmd","display","power-reset","1");
                lock1=true; cmd("cmd","power","set-wakelock","acquire","-d","1","FULL_WAKE_LOCK");
                active=true;
                event("layout "+mode); event("ready");
            }
        } catch(Exception e) { event("error "+e.getMessage()); }
        finally { release(); event("finished"); }
    }
}
