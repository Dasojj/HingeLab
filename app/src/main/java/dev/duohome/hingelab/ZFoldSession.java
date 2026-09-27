package dev.duohome.hingelab;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * ZFoldDuo MIT c8da65d: EmbeddedAdbAngleClient source, AngleRuntime display
 * sequence, HingeOverlayService decisions. See assets/ZFoldDuo-MIT.txt.
 * Lab adapters: authenticated shell transport, named Samsung Binder method,
 * both log formats, generated frames, and a bounded 60-second experiment.
 * No 0.18 recovery or synthetic angles.
 */
public final class ZFoldSession {
    private static final AtomicBoolean running=new AtomicBoolean(true);
    // Upstream controlLock covers one shell command, not the whole handoff.
    // The independent probe deliberately does not take this lock.
    private static final Object controlLock=new Object();
    private static final Semaphore rendered=new Semaphore(0);
    private static volatile boolean owned=false, lock0=false, lock1=false;
    private static final AtomicLong lastUnsubscribe=new AtomicLong();
    private static final AtomicInteger wakes=new AtomicInteger();
    private static long now() { return android.os.SystemClock.elapsedRealtime(); }
    private static String cmd(String... args) throws Exception {
        synchronized(controlLock) { return DualDisplaySession.command(args); }
    }
    private static synchronized void event(String text) {
        System.out.println(text); System.out.flush();
        if(!text.equals("HEARTBEAT") && !text.startsWith("ANGLE ")) android.util.Log.i("HingeLabZFold",text);
    }
    private static void wake(Runnable action) {
        long queued=now();
        synchronized(controlLock) { action.run(); }
        int count=wakes.incrementAndGet();
        event("ZFD wake "+count);
        event("ZFD wake_timing wait="+(now()-queued)+"ms since_unsubscribe="+
            (lastUnsubscribe.get()==0 ? -1 : now()-lastUnsubscribe.get())+"ms");
        if(count>100) throw new IllegalStateException("Repeated unsubscribe loop");
    }
    private static void frame(String phase) throws Exception {
        rendered.drainPermits(); long started=now(); event("DISPLAY frame "+phase);
        if(!rendered.tryAcquire(1000,TimeUnit.MILLISECONDS))
            throw new IOException("Demo frame not committed: "+phase);
        if(!running.get()) throw new InterruptedException("Session stopped");
        event("ZFD frame_ready "+phase+" "+(now()-started)+"ms");
    }
    private static void prepare(boolean closing) throws Exception {
        frame(closing ? "close" : "open");
        String state=cmd("dumpsys","device_state");
        if(!state.contains("identifier=4, name='CONCURRENT_INNER_DEFAULT'") ||
           !state.contains("identifier=5, name='CONCURRENT_OUTER_DEFAULT'") ||
           !state.contains("mOverrideState=Optional.empty")) throw new IOException("Unsupported states or existing override");
        if(cmd("cmd","power","set-wakelock","list").contains("held=true")) throw new IOException("Existing shell wake lock");
        owned=true;
        if(closing) {
            lock0=true; cmd("cmd","power","set-wakelock","acquire","-d","0","FULL_WAKE_LOCK");
            lock1=true; cmd("cmd","power","set-wakelock","acquire","-d","1","FULL_WAKE_LOCK");
        }
        cmd("cmd","device_state","state","4");
        cmd("cmd","display","enable-display","1");
        cmd("cmd","display","power-reset","1");
        event("DISPLAY layout 4"); event("DISPLAY ready");
        if(!closing) { frame("opening_cover"); Thread.sleep(180); }
    }
    private static void releaseLocks() {
        if(lock1) try {
            cmd("cmd","power","set-wakelock","release","-d","1","FULL_WAKE_LOCK"); lock1=false;
        } catch(Exception e) { event("DISPLAY cleanup_error lock1 "+e.getMessage()); }
        if(lock0) try {
            cmd("cmd","power","set-wakelock","release","-d","0","FULL_WAKE_LOCK"); lock0=false;
        } catch(Exception e) { event("DISPLAY cleanup_error lock0 "+e.getMessage()); }
    }
    private static void release(boolean closed) throws Exception {
        if(!owned) return;
        try {
            if(closed && running.get()) frame("handoff");
            if(closed) {
                event("DISPLAY preparing 5");
                cmd("cmd","device_state","state","5");
                for(int i=0;i<20;i++) {
                    if(cmd("dumpsys","device_state").contains("mCommittedState=Optional[DeviceState{identifier=5,")) break;
                    Thread.sleep(16);
                }
                event("DISPLAY layout 5");
                cmd("cmd","device_state","state","reset");
                Thread.sleep(450);
                event("DISPLAY unmask");
                releaseLocks();
            } else {
                releaseLocks();
                cmd("cmd","device_state","state","reset");
            }
            owned=false; event("DISPLAY idle");
        } finally {
            if(owned) {
                releaseLocks();
                cmd("cmd","device_state","state","reset"); owned=false;
                event("DISPLAY idle");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        if(android.os.Process.myUid()!=2000) throw new SecurityException("shell required");
        final String action="hingelab_angle_"+Long.toHexString(System.nanoTime());
        final Runnable request=WallpaperBridge.requester(action,5);
        final Runnable awaken=WallpaperBridge.requester("android.wallpaper.wakingup",5);
        final Process log=new ProcessBuilder("logcat","-v","brief","-T","1",
            "--regex="+action+"|unregisterSensor|registerSensor","SprWallpaper|FoldInteractive:V","*:S")
            .redirectErrorStream(true).start();
        final AtomicReference<Float> latest=new AtomicReference<>();
        final AtomicLong sequence=new AtomicLong();
        final ConcurrentLinkedQueue<Runnable> completions=new ConcurrentLinkedQueue<>();
        final ExecutorService displays=Executors.newSingleThreadExecutor(r -> new Thread(r,"zfd-display-commands"));
        final AtomicBoolean statePending=new AtomicBoolean();
        final AtomicInteger pendingTransitions=new AtomicInteger();
        final AtomicReference<String> deviceState=new AtomicReference<>("");
        final ZFoldPolicy policy=new ZFoldPolicy();
        final long start=now();
        Thread input=new Thread(() -> {
            try { int b; while((b=System.in.read())!=-1) { if(b==80) rendered.release(); } }
            catch(IOException ignored) {} finally { running.set(false); rendered.release(); }
        },"zfd-client");
        input.setDaemon(true); input.start();
        event("ZFD started"); event("SOURCE shell");
        Thread poll=new Thread(() -> {
            try { while(running.get()) { request.run(); Thread.sleep(25); } }
            catch(Exception e) { if(running.get()) event("ZFD error probe "+e.getMessage()); running.set(false); }
        },"zfd-private-probe");
        Thread reader=new Thread(() -> {
            try(BufferedReader lines=new BufferedReader(new InputStreamReader(log.getInputStream(),StandardCharsets.UTF_8))) {
                String line;
                while(running.get() && (line=lines.readLine())!=null) {
                    if(ZFoldLog.INSTANCE.unsubscribed(line)) {
                        lastUnsubscribe.set(now()); event("ZFD unsubscribed");
                        // Upstream readPrivateAngle: wake here, not on the
                        // display driver after its waits and handoff.
                        wake(awaken); continue;
                    }
                    if(line.contains("registerSensor:")) event("ZFD subscription_log "+line.substring(line.indexOf("registerSensor:")));
                    if(!line.contains(action)) continue;
                    Float angle=AngleData.INSTANCE.parse(line);
                    if(angle==null) continue;
                    latest.set(angle); sequence.incrementAndGet();
                    event("ANGLE "+angle+" visible="+line.contains("isVisible=true")+" raw=true");
                }
            } catch(Exception e) { if(running.get()) event("ZFD error reader "+e.getMessage()); }
            finally { running.set(false); }
        },"zfd-private-angle");
        try {
            // Exercise the complete demo -> socket -> helper -> child ACK path
            // before asking the user to fold or acquiring any display override.
            frame("source_ready");
            wake(awaken); reader.start(); poll.start();
            long processed=0,nextState=0,nextHeartbeat=0;
            while(running.get() && now()-start<60000) {
                Runnable completion; while((completion=completions.poll())!=null) completion.run();
                long time=now();
                if(time>=nextHeartbeat) { event("HEARTBEAT"); nextHeartbeat=time+1000; }
                if(time>=nextState && statePending.compareAndSet(false,true)) {
                    nextState=time+80;
                    displays.execute(() -> {
                        try { deviceState.set(cmd("dumpsys","device_state")); }
                        catch(Exception e) { event("ZFD error state "+e.getMessage()); running.set(false); }
                        finally { statePending.set(false); }
                    });
                }
                String state=deviceState.get();
                boolean closed=state.contains("mBaseState=Optional[DeviceState{identifier=0,");
                boolean inner=state.contains("mCommittedState=Optional[DeviceState{identifier=3,") ||
                    state.contains("mCommittedState=Optional[DeviceState{identifier=2,") ||
                    state.contains("mCommittedState=Optional[DeviceState{identifier=4,");
                if(pendingTransitions.get()==0 && closed && policy.getActive()==2) {
                    policy.beginHandoff(); pendingTransitions.incrementAndGet(); event("ZFD closed_handoff");
                    displays.execute(() -> {
                        try { release(true); }
                        catch(Exception e) { event("ZFD error handoff "+e.getMessage()); running.set(false); }
                        finally { completions.add(() -> { policy.closed(); pendingTransitions.decrementAndGet(); }); }
                    });
                }
                if(!state.isEmpty()) {
                    String decision=null;
                    long seq=sequence.get(); Float angle=latest.get();
                    if(decision==null && angle!=null && seq!=processed) {
                        processed=seq; decision=policy.sample(angle,time,inner);
                    }
                    if(decision==null) decision=policy.tick(time);
                    if(decision!=null) {
                        final String next=decision;
                        pendingTransitions.incrementAndGet(); event("ZFD transition "+next+" angle="+angle);
                        displays.execute(() -> {
                            try {
                                if(next.equals("OPEN") || next.equals("CLOSE")) prepare(next.equals("CLOSE"));
                                else release(false);
                            } catch(Exception e) { event("ZFD error transition "+e.getMessage()); running.set(false); }
                            finally { completions.add(() -> pendingTransitions.decrementAndGet()); }
                        });
                    }
                }
                Thread.sleep(8);
            }
        } finally {
            running.set(false); rendered.release(); poll.interrupt(); log.destroy();
            displays.execute(() -> {
                try { release(cmd("dumpsys","device_state").contains("mBaseState=Optional[DeviceState{identifier=0,")); }
                catch(Exception e) { event("DISPLAY cleanup_error "+e.getMessage()); }
                finally { releaseLocks(); event("DISPLAY finished"); }
            });
            displays.shutdown();
            while(!displays.awaitTermination(1,TimeUnit.SECONDS)) event("HEARTBEAT");
            reader.join(2000); poll.join(2000);
        }
    }
}
