package dev.duohome.hingelab;

import android.os.Bundle;
import android.os.IBinder;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

/** Executed only by the paired local ADB shell, using this firmware's AIDL proxy. */
public final class WallpaperBridge {
    public static void main(String[] args) {
        try {
            System.out.println("HL_PID " + android.os.Process.myPid());
            Class<?> api = Class.forName("android.app.IWallpaperManager");
            Class<?> stub = Class.forName("android.app.IWallpaperManager$Stub");
            Method command = null;
            for (Method method : api.getDeclaredMethods()) {
                if (!method.getName().equals("semSendWallpaperCommand")) continue;
                System.out.println("HL_METHOD " + method.toString());
                Class<?>[] p = method.getParameterTypes();
                if (Arrays.equals(p, new Class<?>[]{int.class, String.class, Bundle.class}) ||
                    Arrays.equals(p, new Class<?>[]{int.class, String.class})) command = method;
            }
            if (command == null) throw new IllegalStateException("Нет поддерживаемой сигнатуры semSendWallpaperCommand на этой прошивке");
            // No transaction-number guessing and no custom Parcel serialization.
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String.class).invoke(null, "wallpaper");
            if (binder == null) throw new IllegalStateException("Служба wallpaper недоступна");
            Object manager = stub.getDeclaredMethod("asInterface", IBinder.class).invoke(null, binder);
            send(command, manager, "hingelab_angle");
            System.out.println("HL_READY Запрос угла принят. Подписка датчика поддерживается видимостью системных обоев под Hinge Lab");
            System.out.flush();
            if (args.length > 0 && args[0].equals("once")) return;
            for (int i = 0; i < 3600; i++) {
                send(command, manager, "hingelab_angle");
                if (i % 20 == 0) {
                    System.out.println("HL_TICK");
                    System.out.flush();
                    if (System.out.checkError()) return;
                }
                Thread.sleep(50);
            }
            System.out.println("HL_DONE Сеанс завершён");
        } catch (Throwable error) {
            if (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
            System.out.println("HL_ERROR " + error.getClass().getSimpleName() + ": " + error.getMessage());
            System.out.flush();
            System.exit(1);
        }
    }
    static Runnable requester(String action, int panel) throws Exception {
        Class<?> api = Class.forName("android.app.IWallpaperManager");
        Method selected = null;
        for (Method method : api.getDeclaredMethods()) {
            if (!method.getName().equals("semSendWallpaperCommand")) continue;
            Class<?>[] p = method.getParameterTypes();
            if (Arrays.equals(p, new Class<?>[]{int.class, String.class, Bundle.class}) ||
                Arrays.equals(p, new Class<?>[]{int.class, String.class})) selected = method;
        }
        if (selected == null) throw new IllegalStateException("Unsupported wallpaper interface");
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
            .getDeclaredMethod("getService", String.class).invoke(null, "wallpaper");
        Object manager = Class.forName("android.app.IWallpaperManager$Stub")
            .getDeclaredMethod("asInterface", IBinder.class).invoke(null, binder);
        final Method command = selected;
        return () -> {
            try {
                if (command.getParameterCount()==3) command.invoke(manager,panel,action,null);
                else command.invoke(manager,panel,action);
            }
            catch (Exception e) { throw new IllegalStateException("Wallpaper request failed", e); }
        };
    }
    private static void send(Method command, Object manager, String action) throws Exception {
        if (command.getParameterCount() == 3) command.invoke(manager, 5, action, null);
        else command.invoke(manager, 5, action);
    }
}
