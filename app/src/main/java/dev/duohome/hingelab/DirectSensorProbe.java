package dev.duohome.hingelab;

import android.content.Context;
import android.hardware.*;
import android.os.*;

/** Isolated shell process. Uses SensorManager permission checks, no permission bypass. */
public final class DirectSensorProbe {
    public static void main(String[] args) {
        try {
            if(android.os.Process.myUid()!=2000) throw new SecurityException("Requires shell UID");
            Looper.prepareMainLooper();
            Class<?> threadClass=Class.forName("android.app.ActivityThread");
            Object thread=threadClass.getMethod("systemMain").invoke(null);
            Context system=(Context)threadClass.getMethod("getSystemContext").invoke(thread);
            Context shell=system.createPackageContext("com.android.shell",0);
            SensorManager manager=(SensorManager)shell.getSystemService(Context.SENSOR_SERVICE);
            Sensor sensor=null;
            for(Sensor candidate: manager.getSensorList(Sensor.TYPE_ALL)) {
                if("com.samsung.sensor.folding_angle".equals(candidate.getStringType())) { sensor=candidate; break; }
            }
            if(sensor==null) { System.out.println("DIRECT absent"); return; }
            Handler handler=new Handler(Looper.getMainLooper());
            SensorEventListener listener=new SensorEventListener() {
                public void onAccuracyChanged(Sensor s,int accuracy) {}
                public void onSensorChanged(SensorEvent event) {
                    if(event.values.length==0) return;
                    float value=event.values[0];
                    long age=SystemClock.elapsedRealtimeNanos()-event.timestamp;
                    if(!Float.isFinite(value)||value<0||value>180||age<0||age>2000000000L) return;
                    System.out.println("DIRECT_ANGLE "+value);
                    if(System.out.checkError()) System.exit(0);
                }
            };
            if(!manager.registerListener(listener,sensor,50000,handler)) {
                System.out.println("DIRECT rejected"); return;
            }
            System.out.println("DIRECT subscribed");
            // A registered listener without events is not a successful angle source.
            Thread eof=new Thread(() -> {
                try { while(System.in.read()!=-1) {} } catch(Exception ignored) {}
                handler.post(() -> { manager.unregisterListener(listener); System.exit(0); });
            });
            eof.setDaemon(true); eof.start();
            handler.postDelayed(() -> { manager.unregisterListener(listener); System.exit(0); },600000);
            Looper.loop();
        } catch(Throwable error) {
            if(error instanceof java.lang.reflect.InvocationTargetException && error.getCause()!=null) error=error.getCause();
            System.out.println("DIRECT failed:"+error.getClass().getSimpleName());
        }
    }
}
