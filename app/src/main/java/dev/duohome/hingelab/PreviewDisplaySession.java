package dev.duohome.hingelab;

import java.io.*;
import java.util.concurrent.*;

/** One bounded mode-5 preview experiment. No retries or wallpaper mutations. */
public final class PreviewDisplaySession {
    private static void event(String message) { System.out.println("DISPLAY " + message); System.out.flush(); }
    private static String cmd(String... args) throws Exception { return DualDisplaySession.command(args); }
    public static void main(String[] args) throws Exception {
        if(android.os.Process.myUid()!=2000) throw new SecurityException("shell required");
        CountDownLatch stop=new CountDownLatch(1);
        Thread input=new Thread(() -> { try { while(System.in.read()!=-1) {} } catch(IOException ignored) {} finally { stop.countDown(); } });
        input.setDaemon(true); input.start();
        boolean requested=false, lock0=false, lock1=false;
        Process logs=null;
        try {
            String initial=cmd("dumpsys","device_state");
            if(!initial.contains("identifier=5, name='CONCURRENT_OUTER_DEFAULT'") || !initial.contains("mOverrideState=Optional.empty")) throw new IOException("Unsupported mode or another display experiment active");
            if(!initial.contains("mBaseState=Optional[DeviceState{identifier=0,")) throw new IOException("Start with phone fully closed on cover display");
            if(cmd("cmd","power","set-wakelock","list").contains("held=true")) throw new IOException("Another shell display lock is active");
            logs=new ProcessBuilder("logcat","-v","brief","-T","1","SprWallpaper|FoldInteractive:I","*:S").redirectErrorStream(true).start();
            final Process stream=logs;
            Thread reader=new Thread(() -> {
                try(BufferedReader r=new BufferedReader(new InputStreamReader(stream.getInputStream()))) {
                    String line;
                    while((line=r.readLine())!=null) {
                        if(line.contains("Engine.on") || line.contains("registerSensor:") || line.contains("onDeviceStateChanged:") || line.contains("onVisibilityChanged:") || line.contains("mCurrentAngle=")) event("preview_log "+line);
                    }
                } catch(IOException ignored) {}
            });
            reader.setDaemon(true); reader.start();
            lock0=true; cmd("cmd","power","set-wakelock","acquire","-d","0","FULL_WAKE_LOCK");
            requested=true; cmd("cmd","device_state","state","5");
            boolean committed=false;
            for(int i=0;i<15 && stop.getCount()!=0;i++) {
                if(cmd("dumpsys","device_state").contains("mCommittedState=Optional[DeviceState{identifier=5,")) { committed=true; break; }
                Thread.sleep(100);
            }
            if(!committed || stop.getCount()==0) throw new IOException("Mode 5 did not start");
            cmd("cmd","display","enable-display","1");
            lock1=true; cmd("cmd","power","set-wakelock","acquire","-d","1","FULL_WAKE_LOCK");
            event("preview_ready");
            long deadline=android.os.SystemClock.elapsedRealtime()+60000;
            int tick=0;
            while(android.os.SystemClock.elapsedRealtime()<deadline && !stop.await(1000,TimeUnit.MILLISECONDS)) {
                String state=cmd("dumpsys","device_state");
                if(!state.contains("mCommittedState=Optional[DeviceState{identifier=5,") || state.contains("mOverrideState=Optional.empty")) throw new IOException("Mode 5 cancelled; no automatic restart");
                event("preview_remaining "+Math.max(0,(deadline-android.os.SystemClock.elapsedRealtime())/1000));
                if(++tick%3==0) for(String line:state.split("\\R")) if(line.contains("mBaseState=")) event("preview_posture "+line.trim());
            }
        } catch(Exception e) { event("error "+e); }
        finally {
            if(logs!=null) logs.destroy();
            if(requested) try { cmd("cmd","device_state","state","reset"); } catch(Exception e) { event("cleanup_error reset "+e); }
            if(lock1) release(1);
            if(lock0) release(0);
            if(requested) try {
                String state=cmd("dumpsys","device_state");
                event(state.contains("mOverrideState=Optional.empty") ? "preview_reset_confirmed" : "cleanup_error override remains");
            } catch(Exception e) { event("cleanup_error verification "+e); }
            event("preview_finished");
        }
    }
    private static void release(int display) {
        try { cmd("cmd","power","set-wakelock","release","-d",Integer.toString(display),"FULL_WAKE_LOCK"); }
        catch(Exception e) {
            try {
                for(String line:cmd("cmd","power","set-wakelock","list").split("\\R"))
                    if(line.contains("Display "+display+", wakelock type: FULL_WAKE_LOCK:") && line.contains("held=false")) return;
            } catch(Exception ignored) {}
            event("cleanup_error display="+display+" "+e);
        }
    }
}
