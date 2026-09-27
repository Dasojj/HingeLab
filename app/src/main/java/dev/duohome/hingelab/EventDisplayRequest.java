package dev.duohome.hingelab;

import android.os.*;
import java.lang.reflect.*;
import java.util.concurrent.Semaphore;

/** Shell-only, process-scoped subscription. No settings, wallpaper or power writes. */
public final class EventDisplayRequest implements AutoCloseable {
    public volatile int base=-1, current=-1;
    public volatile boolean cancelled;
    public volatile String failure;
    public volatile long changedAt;
    private final Object service;
    private final Method request, cancel;
    private final Semaphore wake;
    private volatile IBinder owned;
    private final Binder receiver;

    public EventDisplayRequest(Semaphore wake) throws Exception {
        if(android.os.Process.myUid()!=2000) throw new SecurityException("shell required");
        this.wake=wake;
        Class<?> api=Class.forName("android.hardware.devicestate.IDeviceStateManager");
        Class<?> callback=Class.forName("android.hardware.devicestate.IDeviceStateManagerCallback");
        Class<?> stub=Class.forName(callback.getName()+"$Stub");
        int infoCode=code(stub,"onDeviceStateInfoChanged"), activeCode=code(stub,"onRequestActive"), cancelledCode=code(stub,"onRequestCanceled");
        Parcelable.Creator<?> creator=(Parcelable.Creator<?>)Class.forName("android.hardware.devicestate.DeviceStateInfo").getField("CREATOR").get(null);
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"device_state");
        service=Class.forName(api.getName()+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        request=api.getMethod("requestState",IBinder.class,int.class,int.class);
        cancel=api.getMethod("cancelStateRequest");
        receiver=new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if(code==INTERFACE_TRANSACTION) { if(reply!=null) reply.writeString(callback.getName()); return true; }
                if(code!=infoCode && code!=activeCode && code!=cancelledCode) return super.onTransact(code,data,reply,flags);
                try {
                    data.enforceInterface(callback.getName());
                    if(code==infoCode) { Object info=data.readTypedObject(creator); data.enforceNoDataAvail(); accept(info); }
                    else { IBinder token=data.readStrongBinder(); data.enforceNoDataAvail(); if(code==cancelledCode && token==owned) cancelled=true; signal(); }
                } catch(Exception e) { failure=e.toString(); signal(); }
                return true;
            }
        };
        Object listener=Proxy.newProxyInstance(callback.getClassLoader(),new Class<?>[]{callback},(p,m,a)-> {
            if(m.getName().equals("asBinder")) return receiver;
            if(m.getName().equals("hashCode")) return System.identityHashCode(p);
            if(m.getName().equals("equals")) return p==a[0];
            return null;
        });
        // Android 17 returns the initial snapshot while registering; older ABIs fail closed.
        Object initial=api.getMethod("registerCallback",callback).invoke(service,listener);
        synchronized(this) { if(base<0) accept(initial); }
    }
    private static int code(Class<?> stub,String name) throws Exception {
        Field f=stub.getDeclaredField("TRANSACTION_"+name); f.setAccessible(true); return f.getInt(null);
    }
    private synchronized void accept(Object info) throws Exception {
        if(info==null) throw new IllegalStateException("No initial device state");
        Object b=info.getClass().getField("baseState").get(info), c=info.getClass().getField("currentState").get(info);
        base=(int)b.getClass().getMethod("getIdentifier").invoke(b);
        current=(int)c.getClass().getMethod("getIdentifier").invoke(c);
        changedAt=SystemClock.elapsedRealtime(); signal();
    }
    private void signal() { wake.release(); }
    public void enter() throws Exception { enter(4,4); }
    public void enter(int mode,int flags) throws Exception {
        if(mode!=4 && mode!=5) throw new IllegalArgumentException("Unsupported test mode");
        cancelled=false; owned=new Binder(); request.invoke(service,owned,mode,flags);
    }
    public void leave() throws Exception { if(owned!=null) { cancel.invoke(service); owned=null; } }
    @Override public void close() throws Exception { leave(); }
    /** Passive ABI check by default; explicit smoke is bounded to two seconds. */
    public static void main(String[] args) throws Exception {
        try(EventDisplayRequest r=new EventDisplayRequest(new Semaphore(0))) {
            System.out.println("CALLBACK_READY base="+r.base+" current="+r.current);
            if(args.length>0 && args[0].equals("smoke")) {
                String state=DualDisplaySession.command("dumpsys","device_state");
                if(r.base!=3 || !state.contains("mOverrideState=Optional.empty")) throw new IllegalStateException("Normal open phone required");
                r.enter();
                Thread.sleep(1000);
                System.out.println("CALLBACK_ENTER base="+r.base+" current="+r.current+" cancelled="+r.cancelled);
                if(r.current!=4) throw new IllegalStateException("Mode 4 not observed");
                r.leave(); Thread.sleep(1000);
                System.out.println("CALLBACK_RELEASE base="+r.base+" current="+r.current);
                if(r.current!=r.base) throw new IllegalStateException("Release not confirmed");
            } else Thread.sleep(1000);
            if(r.failure!=null) throw new IllegalStateException(r.failure);
        }
    }
}
