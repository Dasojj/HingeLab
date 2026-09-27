package dev.duohome.hingelab;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import android.os.SystemClock;

/** One minute, cover-primary throughout; at most one recovery after cancellation. */
public final class ConstantCoverSession {
    private static void event(String value) { System.out.println("DISPLAY "+value); System.out.flush(); }
    public static void main(String[] args) {
        Semaphore wake=new Semaphore(0);
        AtomicBoolean stopped=new AtomicBoolean();
        Thread input=new Thread(()->{
            try { while(System.in.read()!=-1) {} } catch(IOException ignored) {}
            finally { stopped.set(true); wake.release(); }
        });
        input.setDaemon(true); input.start();
        try {
            String initial=DualDisplaySession.command("dumpsys","device_state");
            if(!initial.contains("identifier=5, name='CONCURRENT_OUTER_DEFAULT'") || !initial.contains("mOverrideState=Optional.empty")) throw new IOException("Unsupported mode or existing override");
            if(DualDisplaySession.command("cmd","power","set-wakelock","list").contains("held=true")) throw new IOException("Existing shell wakelock");
            try(EventDisplayRequest r=new EventDisplayRequest(wake)) {
                long deadline=SystemClock.elapsedRealtime()+60000, pendingUntil=0, recoveryUntil=0, heartbeatAt=0;
                boolean started=false, active=false, recovering=false;
                int restores=0, lastBase=-1, lastCurrent=-1;
                event("waiting Сложи телефон: постоянный режим 5, максимум 60 секунд; угол не проверяем");
                while(!stopped.get() && SystemClock.elapsedRealtime()<deadline) {
                    if(SystemClock.elapsedRealtime()-heartbeatAt>=1000) {
                        heartbeatAt=SystemClock.elapsedRealtime(); System.out.println("HEARTBEAT"); System.out.flush();
                    }
                    if(r.failure!=null) throw new IOException(r.failure);
                    int base=r.base, current=r.current;
                    if(base<0 || base>3) throw new IOException("Unknown physical posture "+base);
                    if(base!=lastBase || current!=lastCurrent) {
                        event("state base="+base+" current="+current+" cancelled="+r.cancelled);
                        lastBase=base; lastCurrent=current;
                    }
                    if(started && r.cancelled && !recovering) {
                        event("cancelled restores="+restores); event("idle");
                        r.leave(); active=false; pendingUntil=0;
                        if(restores>=1) { event("waiting Повторная отмена: завершаем, без цикла перезапусков"); break; }
                        recovering=true; recoveryUntil=SystemClock.elapsedRealtime()+2000;
                    }
                    // Restore only after the normal closed mapping is confirmed. Never swap
                    // the default physical panel to recover from an open-state cancellation.
                    if((!started || recovering) && base==0 && current==0) {
                        if(recovering) restores++;
                        event("request mode=5 flags=0 restores="+restores);
                        r.enter(5,0); started=true; recovering=false;
                        pendingUntil=SystemClock.elapsedRealtime()+2000;
                    }
                    if(started && !recovering && !r.cancelled && r.current==5 && !active) {
                        active=true; pendingUntil=0; event("layout 5"); event("ready");
                        event("held Both panels requested; CLOSED/OPENED do not release the request");
                    }
                    if(pendingUntil!=0 && SystemClock.elapsedRealtime()>pendingUntil) throw new IOException("Mode 5 confirmation timed out");
                    if(recovering && SystemClock.elapsedRealtime()>recoveryUntil) throw new IOException("Normal closed mapping not confirmed; no recovery");
                    wake.tryAcquire(250,TimeUnit.MILLISECONDS); wake.drainPermits();
                }
                event("cleanup Cancel own request; restore normal display policy");
            }
            String state=DualDisplaySession.command("dumpsys","device_state");
            event("cleanup_override_empty="+state.contains("mOverrideState=Optional.empty"));
        } catch(Exception e) { event("error "+e); }
        finally { event("idle"); event("finished"); }
    }
}
