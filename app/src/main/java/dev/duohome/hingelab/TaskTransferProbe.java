package dev.duohome.hingelab;

/** One temporary task move. No launch, duplication, resizing, or persistent overrides. */
public final class TaskTransferProbe {
    private static void log(String value) { System.out.println("TRANSFER "+value); System.out.flush(); }
    public static void main(String[] args) throws Exception {
        if(android.os.Process.myUid()!=2000) throw new SecurityException("shell required");
        Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        Class<?> api=Class.forName("android.app.IActivityTaskManager");
        java.lang.reflect.Method front=api.getMethod("moveTaskToFront",Class.forName("android.app.IApplicationThread"),String.class,int.class,int.class,android.os.Bundle.class);
        if(args.length>0 && args[0].equals("check")) {
            log("API "+api.getMethod("getFocusedRootTaskInfo"));
            log("API "+api.getMethod("moveRootTaskToDisplay",int.class,int.class));
            log("API "+front);
            return;
        }
        java.util.concurrent.CountDownLatch restore=new java.util.concurrent.CountDownLatch(1);
        Thread cancel=new Thread(() -> { try { System.in.read(); } catch(Exception ignored) {} finally { restore.countDown(); } });
        cancel.setDaemon(true); cancel.start();
        Object task=api.getMethod("getFocusedRootTaskInfo").invoke(service);
        if(task==null) { log("NOT_MOVED no focused task"); return; }
        int id=task.getClass().getField("taskId").getInt(task);
        int display=task.getClass().getField("displayId").getInt(task);
        int type=(Integer)task.getClass().getMethod("getActivityType").invoke(task);
        android.content.ComponentName top=(android.content.ComponentName)task.getClass().getField("topActivity").get(task);
        if(display!=0 || type!=1 || top==null || top.getPackageName().equals("dev.duohome.hingelab") || top.getPackageName().equals("com.android.systemui")) {
            log("NOT_MOVED requires foreground standard app on display 0"); return;
        }
        java.lang.reflect.Method move=api.getMethod("moveRootTaskToDisplay",int.class,int.class);
        log("ATTEMPT task="+id+" package="+top.getPackageName());
        if(restore.await(30,java.util.concurrent.TimeUnit.MILLISECONDS)) {
            log("RESTORED skipped_cancelled_before_move task="+id); return;
        }
        boolean attempted=false;
        try {
            attempted=true;
            move.invoke(service,id,1);
            // A moved task is not necessarily resumed on its new display.
            if(restore.getCount()>0) front.invoke(service,null,"com.android.shell",id,0,null);
            Object outside=api.getMethod("getFocusedRootTaskInfo").invoke(service);
            log("MOVED task="+id+" focused="+(outside==null ? -1 : outside.getClass().getField("taskId").getInt(outside)));

            restore.await(2000,java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch(Exception e) { log("REJECTED "+String.valueOf(e.getCause()==null ? e : e.getCause())); }
        finally {
            if(attempted) try {
                move.invoke(service,id,display);
                front.invoke(service,null,"com.android.shell",id,0,null);
                Object focused=api.getMethod("getFocusedRootTaskInfo").invoke(service);
                log("RESTORED task="+id+" focused="+(focused==null ? -1 : focused.getClass().getField("taskId").getInt(focused)));
            }
            catch(Exception e) { log("RESTORE_FAILED "+String.valueOf(e.getCause()==null ? e : e.getCause())); }
        }
    }
}
