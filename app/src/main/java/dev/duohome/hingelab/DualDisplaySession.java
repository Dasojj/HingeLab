package dev.duohome.hingelab;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Fixed, bounded shell-only experiment. Own process also cleans up when its parent dies. */
public final class DualDisplaySession {
    static String command(String... args) throws Exception {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("Command timeout: "+String.join(" ",args)); }
        String result = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.exitValue()!=0 || result.contains("Exception")) throw new IOException("exit="+p.exitValue()+" command="+String.join(" ",args)+" output="+(result.isBlank()?"<empty>":result.trim()));
        return result;
    }
    public static void main(String[] args) throws Exception {
        final boolean leased=args.length>1 && args[1].equals("leased");
        final ClientLease lease=new ClientLease(android.os.SystemClock.elapsedRealtime());
        final boolean noReset=args.length>0 && args[0].equals("legacy18NoReset");
        final boolean legacy=noReset || (args.length>0 && args[0].equals("legacy18"));
        if(android.os.Process.myUid()!=2000) throw new SecurityException("shell required");
        boolean requested=false, lock0=false, lock1=false;
        CountDownLatch stop=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Float> angle=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicLong angleAt=new java.util.concurrent.atomic.AtomicLong();
        Thread input=new Thread(() -> {
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))) {
                String line; while((line=reader.readLine())!=null) {
                    if(line.equals("CLIENT_ALIVE")) { lease.renew(android.os.SystemClock.elapsedRealtime()); continue; }
                    try { float a=Float.parseFloat(line); if(Float.isFinite(a) && a>=0 && a<=180) { angle.set(a); angleAt.set(android.os.SystemClock.elapsedRealtime()); } } catch(NumberFormatException ignored) {}
                }
            } catch(IOException ignored) {} finally { stop.countDown(); }
        });
        input.setDaemon(true); input.start();
        try {
            String state=command("dumpsys","device_state");
            if(!state.contains("identifier=4, name='CONCURRENT_INNER_DEFAULT'") ||
               !state.contains("identifier=5, name='CONCURRENT_OUTER_DEFAULT'") ||
               !state.contains("mOverrideState=Optional.empty")) throw new IOException("Unsupported states or existing override");
            String locks=command("cmd","power","set-wakelock","list");
            if(locks.contains("held=true")) throw new IOException("Existing shell wakelock; experiment cancelled");
            if(stop.getCount()==0) return;
            int mode=!legacy && state.contains("mBaseState=Optional[DeviceState{identifier=0, name='CLOSED'") ? 5 : 4;
            requested=true;
            lock0=true; command("cmd","power","set-wakelock","acquire","-d","0","FULL_WAKE_LOCK");
            command("cmd","device_state","state",Integer.toString(mode));
            boolean committed=false;
            for(int i=0;i<10 && stop.getCount()!=0;i++) {
                if(command("dumpsys","device_state").contains("mCommittedState=Optional[DeviceState{identifier="+mode+", name='CONCURRENT_")) { committed=true; break; }
                Thread.sleep(100);
            }
            if(!committed) throw new IOException("Concurrent state was not committed");
            command("cmd","display","enable-display","1");
            if(!noReset) command("cmd","display","power-reset","1");
            lock1=true; command("cmd","power","set-wakelock","acquire","-d","1","FULL_WAKE_LOCK");
            System.out.println("DISPLAY layout "+mode);
            System.out.println("DISPLAY ready"); System.out.flush();
            long deadline=android.os.SystemClock.elapsedRealtime()+60000;
            int restores=0;
            long nextCheck=0;
            while((leased ? lease.valid(android.os.SystemClock.elapsedRealtime()) : android.os.SystemClock.elapsedRealtime()<deadline) && !stop.await(25,TimeUnit.MILLISECONDS)) {
                long now=android.os.SystemClock.elapsedRealtime();
                Float a=angle.get();
                // Promote the already lit cover BEFORE Samsung's physical CLOSED switch.
                // Keep cover-default afterwards: remapping twice per cycle is unnecessary in this lab.
                if(!legacy && mode==4 && a!=null && a<=12 && now-angleAt.get()<500) {
                    System.out.println("DISPLAY preparing 5"); System.out.flush();
                    Thread.sleep(80);
                    command("cmd","device_state","state","5");
                    mode=5;
                    System.out.println("DISPLAY layout 5"); System.out.flush();
                }
                if(now<nextCheck) continue;
                nextCheck=now+200;
                String current=command("dumpsys","device_state");
                if(current.contains("mOverrideState=Optional.empty")) {
                    if(++restores>4) throw new IOException("Samsung repeatedly cancelled concurrent mode; stopping");
                    command("cmd","device_state","state",Integer.toString(mode));
                    command("cmd","display","enable-display","1");
                    if(legacy && !noReset) command("cmd","display","power-reset","1");
                    System.out.println("DISPLAY restored "+restores); System.out.flush();
                }
            }
        } catch(Exception e) { System.out.println("DISPLAY error "+e.getMessage()); }
        finally {
            if(requested) {
                // Match upstream's closed handoff: promote cover before dropping the override.
                try {
                    String state=command("dumpsys","device_state");
                    if(state.contains("mBaseState=Optional[DeviceState{identifier=0, name='CLOSED'")) {
                        command("cmd","device_state","state","5"); Thread.sleep(250);
                    }
                } catch(Exception ignored) {}
                if(lock1) try { command("cmd","power","set-wakelock","release","-d","1","FULL_WAKE_LOCK"); } catch(Exception ignored) {}
                if(lock0) try { command("cmd","power","set-wakelock","release","-d","0","FULL_WAKE_LOCK"); } catch(Exception ignored) {}
                try { command("cmd","device_state","state","reset"); }
                catch(Exception e) { System.out.println("DISPLAY cleanup_error "+e.getMessage()); }
            }
            System.out.println("DISPLAY finished"); System.out.flush();
        }
    }
}
