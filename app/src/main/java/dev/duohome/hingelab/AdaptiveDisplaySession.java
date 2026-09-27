package dev.duohome.hingelab;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.*;

/** A single minute of OPEN -> mode4 -> CLOSED -> TENT -> mode4 -> OPEN. */
public final class AdaptiveDisplaySession {
    private static final Pattern BASE=Pattern.compile("mBaseState=Optional\\[DeviceState\\{identifier=(\\d+)");
    private static final class Sample {
        final float angle; final long time;
        Sample(float angle) { this.angle=angle; time=android.os.SystemClock.elapsedRealtime(); }
    }
    private boolean requested, lock0, lock1;
    private final boolean direct;
    private boolean leased;
    private volatile boolean earlyEntry;
    private final boolean powerHold;
    private final boolean frameHold;
    private TransitionPowerHold hold;
    private final boolean events;
    private EventDisplayRequest eventBridge;
    private final Semaphore wake=new Semaphore(0);
    private CameraDisplayRequest bridge;
    private AdaptiveDisplaySession(String mode) { frameHold=mode.equals("frameHold"); powerHold=frameHold || mode.equals("powerHold"); events=powerHold || mode.equals("events"); direct=events || mode.equals("cameraRequest"); }
    private static String cmd(String... args) throws Exception { return DualDisplaySession.command(args); }
    private static void event(String message) { System.out.println("DISPLAY "+message); System.out.flush(); }
    public static void main(String[] args) { AdaptiveDisplaySession session=new AdaptiveDisplaySession(args.length>0 ? args[0] : ""); session.leased=args.length>1 && args[1].equals("leased"); session.run(); }
    private void run() {
        if(android.os.Process.myUid()!=2000) throw new SecurityException("shell required");
        CountDownLatch stop=new CountDownLatch(1);
        AtomicReference<Sample> sample=new AtomicReference<>();
        final ClientLease lease=new ClientLease(android.os.SystemClock.elapsedRealtime());
        Thread input=new Thread(() -> {
            try(BufferedReader r=new BufferedReader(new InputStreamReader(System.in))) {
                String line; while((line=r.readLine())!=null) try {
                    if(line.equals("ENTER_EARLY")) { earlyEntry=true; event("entry_threshold 160"); continue; }
                    if(line.equals("CLIENT_ALIVE")) { lease.renew(android.os.SystemClock.elapsedRealtime()); continue; }
                    float value=Float.parseFloat(line);
                    if(Float.isFinite(value) && value>=0 && value<=180) { sample.set(new Sample(value)); if(events) wake.release(); }
                } catch(NumberFormatException ignored) {}
            } catch(IOException ignored) {} finally { stop.countDown(); wake.release(); }
        });
        input.setDaemon(true); input.start();
        boolean active=false, blocked=false, closeHoldArmed=true;
        int previous=-1;
        try {
            String initial=cmd("dumpsys","device_state");
            if(!initial.contains("identifier=4, name='CONCURRENT_INNER_DEFAULT'") || !initial.contains("mOverrideState=Optional.empty")) throw new IOException("Unsupported mode or existing override");
            if(cmd("cmd","power","set-wakelock","list").contains("held=true")) throw new IOException("Existing shell wakelock");
            if(events) { eventBridge=new EventDisplayRequest(wake); event("request_api event callbacks flags=4; no dumpsys polling"); }
            else if(direct) { bridge=new CameraDisplayRequest(); event("request_api direct flags=4; no explicit display power commands"); }
            if(powerHold) { hold=new TransitionPowerHold(); event("power_hold enabled ttl_ms=700; both logical IDs only during handoff"); }
            event("idle"); event("waiting Normal displays; enter at angle <=150 or physical TENT; release at CLOSED or fresh angle >=178");
            long deadline=android.os.SystemClock.elapsedRealtime()+60000;
            while((leased ? lease.valid(android.os.SystemClock.elapsedRealtime()) : android.os.SystemClock.elapsedRealtime()<deadline) && stop.getCount()!=0) {
                if(events) { wake.tryAcquire(1000,TimeUnit.MILLISECONDS); wake.drainPermits(); }
                else if(stop.await(100,TimeUnit.MILLISECONDS)) break;
                if(stop.getCount()==0) break;
                String state=events ? "" : cmd("dumpsys","device_state");
                int base;
                if(events) { if(eventBridge.failure!=null) throw new IOException(eventBridge.failure); base=eventBridge.base; }
                else { Matcher m=BASE.matcher(state); if(!m.find()) throw new IOException("Physical posture unavailable"); base=Integer.parseInt(m.group(1)); }
                if(base<0 || base>3) throw new IOException("Unknown physical posture "+base);
                if(previous!=base) { event("posture "+base); previous=base; }
                Sample s=sample.get(); Float angle=s==null?null:s.angle;
                long age=s==null?Long.MAX_VALUE:android.os.SystemClock.elapsedRealtime()-s.time;
                if(base==0) blocked=false;
                if(TransitionHoldPolicy.rearm(angle,age)) closeHoldArmed=true;
                if(powerHold && closeHoldArmed && TransitionHoldPolicy.closing(active,angle,age)) {
                    hold.pulse(); closeHoldArmed=false; event("power_hold closing angle="+angle+" ttl_ms=700");
                }
                if(active && AdaptiveDisplayPolicy.shouldRelease(base,angle,age)) {
                    event("release base="+base+" angle="+angle+" age="+age);
                    release(); active=false; sample.set(null); event("idle");
                    event("waiting "+(base==0?"Closed; waiting for TENT":"Open; waiting for folding"));
                    continue;
                }
                if(active && (events ? eventBridge.cancelled : state.contains("mOverrideState=Optional.empty"))) {
                    event("cancelled base="+base+"; no automatic retry until CLOSED");
                    release(); active=false; blocked=true; sample.set(null); event("idle"); continue;
                }
                if(active || blocked || !AdaptiveDisplayPolicy.shouldEnter(base,angle,age,earlyEntry)) continue;
                event("activating base="+base+" angle="+angle+" age="+age);
                long activationStart=android.os.SystemClock.elapsedRealtime();
                if(!direct) { lock0=true; cmd("cmd","power","set-wakelock","acquire","-d","0","FULL_WAKE_LOCK"); }
                if(powerHold && (base==1 || base==2)) { hold.pulse(); event("power_hold opening ttl_ms=700"); }
                requested=true;
                if(events) eventBridge.enter(); else if(direct) bridge.enter(); else cmd("cmd","device_state","state","4");
                boolean ready=false;
                long enterDeadline=android.os.SystemClock.elapsedRealtime()+1500;
                for(int i=0;(events ? android.os.SystemClock.elapsedRealtime()<enterDeadline : i<12) && stop.getCount()!=0;i++) {
                    if(events) {
                        if(eventBridge.failure!=null) throw new IOException(eventBridge.failure);
                        if(eventBridge.base==0 || eventBridge.cancelled) break;
                        if(eventBridge.current==4) { ready=true; break; }
                        wake.tryAcquire(50,TimeUnit.MILLISECONDS); wake.drainPermits(); continue;
                    }
                    String check=cmd("dumpsys","device_state");
                    if(check.contains("mBaseState=Optional[DeviceState{identifier=0,")) break;
                    if(check.contains("mCommittedState=Optional[DeviceState{identifier=4,")) { ready=true; break; }
                    Thread.sleep(80);
                }
                if(!ready) { release(); blocked=true; event("idle"); event("waiting Transition interrupted; close to retry"); continue; }
                if(!direct) {
                    cmd("cmd","display","enable-display","1");
                    lock1=true; cmd("cmd","power","set-wakelock","acquire","-d","1","FULL_WAKE_LOCK");
                }
                event((events ? "state_confirmed_ms " : "activation_ms ")+(android.os.SystemClock.elapsedRealtime()-activationStart));
                active=true; sample.set(null); event("layout 4"); event("ready");
            }
        } catch(Exception e) { event("error "+e); }
        finally { release(); if(hold!=null) try { hold.close(); event("power_hold released"); } catch(Exception e) { event("cleanup_error power "+e); } if(bridge!=null) bridge.close(); if(eventBridge!=null) try { eventBridge.close(); } catch(Exception e) { event("cleanup_error "+e); } event("finished"); }
    }
    private void release() {
        if(requested) try { if(events) eventBridge.leave(); else if(direct) bridge.leave(); else cmd("cmd","device_state","state","reset"); requested=false; } catch(Exception e) { event("cleanup_error reset "+e); }
        if(lock1 && releaseLock(1)) lock1=false;
        if(lock0 && releaseLock(0)) lock0=false;
    }
    private boolean releaseLock(int display) {
        try { cmd("cmd","power","set-wakelock","release","-d",Integer.toString(display),"FULL_WAKE_LOCK"); return true; }
        catch(Exception e) {
            try { for(String line:cmd("cmd","power","set-wakelock","list").split("\\R"))
                if(line.contains("Display "+display+", wakelock type: FULL_WAKE_LOCK:") && line.contains("held=false")) return true;
            } catch(Exception ignored) {}
            event("cleanup_error display="+display+" "+e); return false;
        }
    }
}
