package dev.duohome.hingelab;

import android.content.Context;
import android.hardware.*;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

/** Read-only, bounded investigation. Never requests display states or wake locks. */
public final class PostureProbe {
    static final Pattern BASE=Pattern.compile("mBaseState=Optional\\[DeviceState\\{identifier=(\\d+), name='([^']+)'");
    static final Pattern LID=Pattern.compile("mIsLidOpen = (true|false)");
    static final AtomicBoolean running=new AtomicBoolean(true);
    static void event(String value) {
        System.out.println("POSTURE "+SystemClock.elapsedRealtime()+" "+value);
        if(System.out.checkError()) running.set(false);
    }
    public static void main(String[] args) throws Exception {
        if(android.os.Process.myUid()!=2000) throw new SecurityException("shell required");
        Looper.prepareMainLooper();
        Handler handler=new Handler(Looper.getMainLooper());
        SensorManager manager=null;
        SensorEventListener listener=new SensorEventListener() {
            public void onAccuracyChanged(Sensor s,int a) {}
            public void onSensorChanged(SensorEvent e) {
                if(SystemClock.elapsedRealtimeNanos()-e.timestamp>1000000000L) return;
                event("SENSOR type="+e.sensor.getType()+" value="+Arrays.toString(e.values));
            }
        };
        try {
            Class<?> at=Class.forName("android.app.ActivityThread");
            Context system=(Context)at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
            manager=(SensorManager)system.createPackageContext("com.android.shell",0).getSystemService(Context.SENSOR_SERVICE);
            for(Sensor s:manager.getSensorList(Sensor.TYPE_ALL)) {
                if(s.getType()!=36 && s.getType()!=65695 && s.getType()!=65697) continue;
                try { event("SUBSCRIBE "+s.getType()+" "+s.getName()+" accepted="+manager.registerListener(listener,s,50000,handler)); }
                catch(Exception e) { event("SUBSCRIBE "+s.getType()+" rejected="+e.getClass().getSimpleName()); }
            }
        } catch(Exception e) { event("SENSORS unavailable="+e.getClass().getSimpleName()); }
        final SensorManager sensors=manager;
        Thread input=new Thread(() -> { try { while(System.in.read()!=-1) {} } catch(IOException ignored) {} finally { running.set(false); } });
        input.setDaemon(true); input.start();
        new Thread(() -> {
            String last="";
            long until=SystemClock.elapsedRealtime()+90000;
            try {
                while(running.get() && SystemClock.elapsedRealtime()<until) {
                    long began=SystemClock.elapsedRealtime();
                    String state=DualDisplaySession.command("dumpsys","device_state");
                    Matcher b=BASE.matcher(state), l=LID.matcher(state);
                    String next=(b.find()?"BASE "+b.group(1)+" "+b.group(2):"BASE unavailable")+" lid="+(l.find()?l.group(1):"unknown");
                    if(!next.equals(last)) { event(next+" query_ms="+(SystemClock.elapsedRealtime()-began)); last=next; }
                    System.out.println("HEARTBEAT");
                    if(System.out.checkError()) break;
                    Thread.sleep(150);
                }
            } catch(Exception e) { event("ERROR "+e.getClass().getSimpleName()); }
            finally { handler.post(() -> { if(sensors!=null) sensors.unregisterListener(listener); event("FINISHED"); Looper.myLooper().quitSafely(); }); }
        },"passive-posture").start();
        Looper.loop();
    }
}
