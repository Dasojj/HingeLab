package dev.duohome.hingelab;

import android.os.Handler;
import android.os.HandlerThread;
import java.lang.reflect.*;
import java.util.concurrent.*;

/** Shell-owned request using the camera's observed flag, not a system-app identity. */
public final class CameraDisplayRequest implements AutoCloseable {
    private final HandlerThread thread=new HandlerThread("hingelab-state-request");
    private final Object manager;
    private final Method request, cancel;
    private final Object state4;
    public CameraDisplayRequest() throws Exception {
        if(android.os.Process.myUid()!=2000) throw new SecurityException("shell required");
        thread.start();
        try {
            Class<?> requestClass=Class.forName("android.hardware.devicestate.DeviceStateRequest");
            Field flag=requestClass.getDeclaredField("FLAG_NO_USE_NOTIFY"); flag.setAccessible(true);
            int flags=flag.getInt(null);
            if(flags!=4) throw new IllegalStateException("Camera flag differs on this firmware: "+flags);
            Object builder=requestClass.getMethod("newBuilder",int.class).invoke(null,4);
            builder.getClass().getMethod("setFlags",int.class).invoke(builder,flags);
            state4=builder.getClass().getMethod("build").invoke(builder);
            Class<?> global=Class.forName("android.hardware.devicestate.DeviceStateManagerGlobal");
            FutureTask<Object> create=new FutureTask<>(() -> global.getMethod("getInstance").invoke(null));
            new Handler(thread.getLooper()).post(create);
            manager=create.get(3,TimeUnit.SECONDS);
            request=global.getMethod("requestState",requestClass,Executor.class,Class.forName("android.hardware.devicestate.DeviceStateRequest$Callback"));
            cancel=global.getMethod("cancelStateRequest");
        } catch(Exception e) { thread.quitSafely(); throw e; }
    }
    public void enter() throws Exception { invoke(request,state4,null,null); }
    public void leave() throws Exception { invoke(cancel); }
    private void invoke(Method method,Object... args) throws Exception {
        try { method.invoke(manager,args); }
        catch(InvocationTargetException e) {
            Throwable cause=e.getCause();
            if(cause instanceof Exception) throw (Exception)cause;
            throw e;
        }
    }
    @Override public void close() { try { leave(); } catch(Exception e) { System.out.println("DISPLAY cleanup_error direct "+e); } finally { thread.quitSafely(); } }

    /** Two-second on-device acceptance/cleanup check; no automatic retries. */
    public static void main(String[] args) throws Exception {
        String initial=DualDisplaySession.command("dumpsys","device_state");
        if(!initial.contains("mOverrideState=Optional.empty") || !initial.contains("mBaseState=Optional[DeviceState{identifier=3,")) throw new IllegalStateException("Smoke test requires normal open phone");
        try(CameraDisplayRequest bridge=new CameraDisplayRequest()) {
            bridge.enter();
            System.out.println("REQUEST_ACCEPTED pid="+android.os.Process.myPid()+" flags=4");
            Thread.sleep(2000);
            String state=DualDisplaySession.command("dumpsys","device_state");
            for(String line:state.split("\\R")) if(line.contains("mCommittedState=") || line.startsWith("Request:")) System.out.println(line);
        }
        Thread.sleep(400);
        System.out.println("RESET_CONFIRMED="+DualDisplaySession.command("dumpsys","device_state").contains("mOverrideState=Optional.empty"));
    }
}
