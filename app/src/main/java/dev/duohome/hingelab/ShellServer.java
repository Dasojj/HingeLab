package dev.duohome.hingelab;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;

/** USB-started shell process. Only authenticated angle streaming, no command execution API. */
public final class ShellServer {
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new SecurityException("Requires shell UID");
        byte[] secret = Files.readAllBytes(Paths.get(args[0]));
        try (ServerSocket server = new ServerSocket(43987, 1, InetAddress.getByName("127.0.0.1"))) {
            System.out.println("READY uid=2000 pid=" + android.os.Process.myPid());
            while (true) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    InputStream input = socket.getInputStream();
                    byte[] supplied = new byte[secret.length];
                    new DataInputStream(input).readFully(supplied);
                    if (!MessageDigest.isEqual(secret, supplied)) continue;
                    socket.setSoTimeout(0);
                    PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                    output.println("HELLO shell uid=2000");
                    socket.setSoTimeout(3000);
                    int wireMode = input.read();
                    final boolean persistent = wireMode==171 || wireMode==186 || wireMode==187 || wireMode==188;
                    final int panel = persistent ? wireMode-100 : wireMode;
                    if(persistent) output.println("PERSISTENT mode="+panel+" lease_ms=5000");
                    if(panel==254) { output.println("STOPPED"); return; }
                    if(panel==75 || panel==80 || panel==85) {
                        socket.setSoTimeout(0);
                        ProcessBuilder pb=new ProcessBuilder("app_process","/system/bin", panel==85 ? "dev.duohome.hingelab.ConstantCoverSession" : panel==80 ? "dev.duohome.hingelab.PreviewDisplaySession" : "dev.duohome.hingelab.PostureProbe");
                        pb.environment().put("CLASSPATH",System.getenv("CLASSPATH"));
                        Process posture=pb.redirectErrorStream(true).start();
                        Thread end=new Thread(() -> { try { while(input.read()!=-1) {} } catch(IOException ignored) {} finally { try { posture.getOutputStream().close(); } catch(IOException ignored) {} } });
                        end.setDaemon(true); end.start();
                        try(BufferedReader r=new BufferedReader(new InputStreamReader(posture.getInputStream(),StandardCharsets.UTF_8))) {
                            String line; while((line=r.readLine())!=null) { output.println(line); if(output.checkError()) break; }
                        } finally { try { posture.getOutputStream().close(); } catch(IOException ignored) {} }
                        continue;
                    }
                    if(panel==70) { socket.setSoTimeout(0); runZFold(input,output); continue; }
                    boolean dual = panel == 69 || panel == 71 || panel == 72 || panel == 77 || panel == 79 || panel == 81 || panel == 82 || panel == 83 || panel == 84 || panel == 86 || panel == 87 || panel == 88;
                    if (panel != 5 && panel != 17 && !dual) continue;
                    socket.setSoTimeout(0);
                    java.util.concurrent.atomic.AtomicInteger activePanel = new java.util.concurrent.atomic.AtomicInteger(dual ? 5 : panel);
                    // Fixed diagnostic only; no client-supplied shell commands.
                    output.println("META wallpaper=" + wallpaperAvailable());
                    String action = "hingelab_angle_" + Long.toHexString(System.nanoTime());
                    Process log = new ProcessBuilder("logcat", "-v", "brief", "-T", "1", "--regex=" + action, "*:V").redirectErrorStream(true).start();
                    output.println("SESSION " + action);
                    java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
                    Process displayProcess;
                    if(dual) {
                        ProcessBuilder builder=new ProcessBuilder("app_process","/system/bin",(panel==82 || panel==83 || panel==84 || panel==86 || panel==87 || panel==88) ? "dev.duohome.hingelab.AdaptiveDisplaySession" : (panel==77 || panel==79) ? "dev.duohome.hingelab.TentDisplaySession" : "dev.duohome.hingelab.DualDisplaySession",(panel==87 || panel==88) ? "frameHold" : panel==86 ? "powerHold" : panel==84 ? "events" : panel==83 ? "cameraRequest" : panel==81 ? "legacy18NoReset" : panel==79 ? "coverFirst" : panel==71 || panel==72 ? "legacy18" : "current19");
                        if(persistent) builder.command().add("leased");
                        builder.environment().put("CLASSPATH",System.getenv("CLASSPATH"));
                        displayProcess=builder.redirectErrorStream(true).start();
                        Thread displayReader=new Thread(() -> {
                            try(BufferedReader r=new BufferedReader(new InputStreamReader(displayProcess.getInputStream(),StandardCharsets.UTF_8))) {
                                String line; while((line=r.readLine())!=null) if(line.startsWith("DISPLAY ")) output.println(line);
                            } catch(IOException ignored) {}
                            finally { running.set(false); log.destroy(); }
                        },"dual-display-guard");
                        displayReader.setDaemon(true); displayReader.start();
                    } else displayProcess=null;
                    java.util.concurrent.atomic.AtomicBoolean directReceived = new java.util.concurrent.atomic.AtomicBoolean(false);
                    ProcessBuilder sensorBuilder = new ProcessBuilder("app_process", "/system/bin", "dev.duohome.hingelab.DirectSensorProbe");
                    sensorBuilder.environment().put("CLASSPATH", System.getenv("CLASSPATH"));
                    Process sensorProcess = sensorBuilder.redirectErrorStream(true).start();
                    Thread sensorReader = new Thread(() -> {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(sensorProcess.getInputStream(),StandardCharsets.UTF_8))) {
                            String event;
                            while(running.get() && (event=reader.readLine())!=null) {
                                if(event.startsWith("DIRECT_ANGLE ")) {
                                    Float value=DirectAngleData.INSTANCE.parse(event);
                                    if(value!=null) {
                                        directReceived.set(true);
                                        output.println("DIRECT_ANGLE "+value);
                                    }
                                } else if(event.startsWith("DIRECT ")) output.println(event);
                            }
                        } catch(Exception ignored) {}
                        finally { if(!directReceived.get()) output.println("DIRECT unavailable"); }
                    },"direct-sensor-reader");
                    sensorReader.setDaemon(true); sensorReader.start();
                    Thread polling = new Thread(() -> {
                        try {
                            Runnable mainRequest = WallpaperBridge.requester(action+"05", 5);
                            Runnable coverRequest = WallpaperBridge.requester(action+"11", 17);
                            output.println("SOURCE shell");
                            long began = android.os.SystemClock.elapsedRealtime();
                            int ticks = 0;
                            while (running.get() && android.os.SystemClock.elapsedRealtime()-began < (persistent ? Long.MAX_VALUE : 600000)) {
                                mainRequest.run();
                                coverRequest.run();
                                if (ticks++ % 20 == 0) output.println("HEARTBEAT");
                                if (output.checkError()) break;
                                Thread.sleep(50);
                            }
                        } catch (InterruptedException ignored) {
                        } catch (Exception e) { output.println("ERROR wallpaper_request " + e.getClass().getSimpleName()); }
                        finally { log.destroy(); }
                    }, "wallpaper-requests");
                    polling.setDaemon(true);
                    polling.start();
                    final java.util.concurrent.atomic.AtomicReference<Process> transferChild=new java.util.concurrent.atomic.AtomicReference<>();
                    final java.util.concurrent.atomic.AtomicBoolean restoreRequested=new java.util.concurrent.atomic.AtomicBoolean(false);
                    final java.util.concurrent.atomic.AtomicInteger focusQuery=new java.util.concurrent.atomic.AtomicInteger();
                    final java.util.concurrent.atomic.AtomicBoolean transferUsed=new java.util.concurrent.atomic.AtomicBoolean(false);
                    Thread disconnect = new Thread(() -> {
                        try { int next; while ((next=input.read()) != -1) { if(persistent && next==200) { if(displayProcess!=null) synchronized(displayProcess) { displayProcess.getOutputStream().write("CLIENT_ALIVE\n".getBytes(StandardCharsets.UTF_8)); displayProcess.getOutputStream().flush(); } } else if(persistent && next==201 && transferUsed.compareAndSet(false,true)) {
                            restoreRequested.set(false); transferChild.set(null);
                            Thread transfer=new Thread(() -> {
                                boolean terminal=false;
                                try {
                                    if(restoreRequested.get()) { terminal=true; transferUsed.set(false); output.println("TRANSFER RESTORED skipped_before_launch"); return; }
                                    ProcessBuilder pb=new ProcessBuilder("app_process","/system/bin","dev.duohome.hingelab.TaskTransferProbe");
                                    pb.environment().put("CLASSPATH",System.getenv("CLASSPATH"));
                                    Process child=pb.redirectErrorStream(true).start();
                                    transferChild.set(child);
                                    if(restoreRequested.get()) child.getOutputStream().close();
                                    try(BufferedReader lines=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8))) {
                                        String value; while((value=lines.readLine())!=null) {
                                            if(TransferResultPolicy.INSTANCE.safeCompletion(value)) { transferUsed.set(false); terminal=true; }
                                            if(TransferResultPolicy.INSTANCE.failedCompletion(value)) terminal=true;
                                            output.println(value.startsWith("TRANSFER ") ? value : "TRANSFER ERROR "+value);
                                        }
                                    }
                                } catch(Exception e) { output.println("TRANSFER ERROR "+e); }
                                finally {
                                    // EOF/launch/read failure is not confirmation that a moved task came back.
                                    if(!terminal && !restoreRequested.get()) output.println("TRANSFER RESTORE_FAILED missing_terminal_result");
                                    else if(!terminal && transferUsed.get()) output.println("TRANSFER RESTORE_FAILED cancelled_without_terminal_result");
                                }
                            },"one-task-transfer"); transfer.setDaemon(true); transfer.start();
                        } else if(persistent && next==202) {
                            restoreRequested.set(true); Process child=transferChild.get();
                            if(child!=null) try { child.getOutputStream().close(); } catch(IOException ignored) {}
                        } else if(persistent && next==204) {
                            final int query=focusQuery.incrementAndGet();
                            Thread focus=new Thread(() -> {
                                try {
                                    Class<?> api=Class.forName("android.app.IActivityTaskManager");
                                    Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
                                    Object task=api.getMethod("getFocusedRootTaskInfo").invoke(service);
                                    android.content.ComponentName top=task==null ? null : (android.content.ComponentName)task.getClass().getField("topActivity").get(task);
                                    int display=task==null ? -1 : task.getClass().getField("displayId").getInt(task);
                                    output.println("FOCUS query="+query+" display="+display+" package="+(top==null ? "unknown" : top.getPackageName()));
                                } catch(Exception e) { output.println("FOCUS query="+query+" display=-1 package=unknown"); }
                            },"check-foreground-task"); focus.setDaemon(true); focus.start();
                        } else if(persistent && next==203 && displayProcess!=null) {
                            synchronized(displayProcess) { displayProcess.getOutputStream().write("ENTER_EARLY\n".getBytes(StandardCharsets.UTF_8)); displayProcess.getOutputStream().flush(); }
                        } else if(next==5 || next==17) { if(!dual) activePanel.set(next); } else break; } } catch (IOException ignored) {}
                        finally {
                            restoreRequested.set(true); Process child=transferChild.get();
                            if(child!=null) try { child.getOutputStream().close(); child.waitFor(2500,java.util.concurrent.TimeUnit.MILLISECONDS); } catch(Exception ignored) {}
                            running.set(false); polling.interrupt(); sensorProcess.destroy(); log.destroy(); stopDisplay(displayProcess);
                        }
                    }, "client-disconnect");
                    disconnect.setDaemon(true);
                    disconnect.start();
                    try (BufferedReader lines = new BufferedReader(new InputStreamReader(log.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        long rawAt=0, rawCount=0;
                        java.util.Set<Float> rawValues=new java.util.HashSet<>();
                        while ((line = lines.readLine()) != null) {
                            Float angle = AngleData.INSTANCE.parse(line);
                            int source = line.contains(action+"05") ? 5 : line.contains(action+"11") ? 17 : 0;
                            if (!directReceived.get() && angle != null && source==activePanel.get()) {
                                if(angle<=40f) { restoreRequested.set(true); Process child=transferChild.get(); if(child!=null) try { child.getOutputStream().close(); } catch(IOException ignored) {} }
                                if(panel==79 || panel==87 || panel==88) {
                                    rawCount++; rawValues.add(angle);
                                    long now=android.os.SystemClock.elapsedRealtime();
                                    if(now-rawAt>=((panel==87 || panel==88) ? 5000 : 1000)) {
                                        rawAt=now;
                                        output.println("DISPLAY source_sample raw="+rawCount+" distinct="+rawValues.size()+" angle="+angle+" visible="+line.contains("isVisible=true"));
                                    }
                                }
                                // A fresh log line from an invisible engine can contain a frozen angle.
                                if(!line.contains("isVisible=true")) { output.println("CACHE source="+source); continue; }
                                if(displayProcess!=null) {
                                    try { synchronized(displayProcess) { displayProcess.getOutputStream().write((angle+"\n").getBytes(StandardCharsets.UTF_8)); displayProcess.getOutputStream().flush(); } } catch(IOException ignored) {}
                                }
                                output.println("ANGLE " + angle + " visible=" + (line.contains("isVisible=true") ? "true" : line.contains("isVisible=false") ? "false" : "unknown"));
                                if (output.checkError()) break;
                            }
                        }
                    } finally {
                            restoreRequested.set(true); Process child=transferChild.get();
                            if(child!=null) try { child.getOutputStream().close(); child.waitFor(2500,java.util.concurrent.TimeUnit.MILLISECONDS); } catch(Exception ignored) {}
                            running.set(false); polling.interrupt(); sensorProcess.destroy(); log.destroy(); stopDisplay(displayProcess);
                        }
                } catch (Exception e) { System.out.println("Client ended: " + e.getClass().getSimpleName()); }
            }
        }
    }

    private static void runZFold(InputStream input, PrintWriter output) throws Exception {
        ProcessBuilder builder=new ProcessBuilder("app_process","/system/bin","dev.duohome.hingelab.ZFoldSession");
        builder.environment().put("CLASSPATH",System.getenv("CLASSPATH"));
        Process child=builder.redirectErrorStream(true).start();
        Thread disconnect=new Thread(() -> {
            try {
                int b;
                while((b=input.read())!=-1) {
                    if(b==80) { child.getOutputStream().write(b); child.getOutputStream().flush(); }
                }
            } catch(IOException ignored) {}
            finally { stopDisplay(child); }
        },"zfold-disconnect");
        disconnect.setDaemon(true); disconnect.start();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8))) {
            String line; while((line=reader.readLine())!=null) { output.println(line); if(output.checkError()) break; }
        } finally { stopDisplay(child); }
    }

    private static void stopDisplay(Process process) {
        // EOF requests cleanup; never kill the guard before its reset completes.
        if(process!=null) try { process.getOutputStream().close(); } catch(IOException ignored) {}
    }

    private static String wallpaperAvailable() {
        Process process = null;
        try {
            process = new ProcessBuilder("dumpsys", "wallpaper").redirectErrorStream(true).start();
            final Process child = process;
            Thread timeout = new Thread(() -> {
                try { Thread.sleep(2000); child.destroy(); } catch (InterruptedException ignored) {}
            });
            timeout.setDaemon(true); timeout.start();
            boolean found = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("mWallpaperComponent=") && line.contains("com.samsung.android.wallpaper.live.fold.FoldInteractive")) found = true;
                }
            }
            return found ? "present" : process.waitFor() == 0 ? "absent" : "unknown";
        } catch (Exception ignored) { return "unknown"; }
        finally { if (process != null) process.destroy(); }
    }
}
