package dev.duohome.hingelab

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.DisplayManager
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityEvent
import dev.duohome.hingelab.effect.*
import kotlin.math.abs
import kotlin.math.exp

/** Independent overlay host: bounded demos and their window lifecycle are untouched. */
class EverywhereAnimationService : AccessibilityService() {
    companion object {
        var instance: EverywhereAnimationService?=null; private set
        var status="Выключено"; private set
        private val journal=ArrayDeque<String>()
        fun report()="Hinge Lab 2.0 beta 4 · поверх приложений\n$status\n"+journal.joinToString("\n")
        private fun note(message:String) { android.util.Log.i("HingeEverywhere",message); journal.addLast("${SystemClock.elapsedRealtime()} · $message"); while(journal.size>100) journal.removeFirst() }
        fun name(mode:Int)=when(mode) { 71->"0.18 · оба экрана"; 86->"режим 4 · питание 700 мс"; 87->"режим 4 · непрерывный кадр"; else->"режим 4 · без Presentation" }
        fun stop(context:Context) {
            context.getSharedPreferences("everywhere",0).edit().remove("mode").apply()
            instance?.disable()
        }
    }
    private val handler=Handler(Looper.getMainLooper())
    private val screens by lazy { getSystemService(DisplayManager::class.java) }
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val lock by lazy { getSystemService(KeyguardManager::class.java) }
    private var reconnects=0
    private var retryAfter=0L
    private var lastContentRefresh=0L
    private var fallbackExperiment=0
    private var closingMotion=false
    private var transferGate=TransferCycleGate()
    private var transferUntil=0L
    private var restoredAt=0L
    private var focusQuery=0
    private var pendingFocus:((String?)->Unit)?=null
    private var pendingFocusId=0
    private var panelDiagnostic=false
    private var diagnosticRounds=0
    private var diagnosticAt=0L
    private var markerStarted=0L
    private var markerHidden=false
    private var markerView:android.widget.TextView?=null
    private var markerManager:WindowManager?=null
    private var mode=0
    private var lab=true
    private var foreground=""
    private var probe:EverywhereProbe?=null
    private var generation=0
    private var angle=180f
    private var shown=180f
    private var received=0L
    private var moved=0L
    private var captureAt=0L
    private var capturePending=false
    private var captureGeneration=0
    private var lastFrame=0L
    private var ready=false
    private var lastSampleLog=0L
    private var contentEpoch=0
    private var primaryWindowId=-1
    private var windowCaptureBlocked=false
    private var primaryShape:PanelSize?=null
    private var primaryStableAfter=0L
    private var capturesRemaining=0
    private var base=-1
    private var moving=false
    private var gestureValidated=false
    private val exhausted=mutableSetOf<Int>()
    private val missing=mutableSetOf<PanelSize>()
    private val nativeFrames=NativePanelFrames<Bitmap>()
    private val windows=OwnedOverlays<Int,Overlay> { w ->
        try { w.manager.removeViewImmediate(w.view) }
        catch(e:IllegalArgumentException) { note("OVERLAY already removed") }
        w.view.release()
        if(w.id==Display.DEFAULT_DISPLAY) primaryStableAfter=maxOf(primaryStableAfter,SystemClock.elapsedRealtime()+350)
        note("OVERLAY removed display=${w.id}")
    }
    private var anchor:View?=null
    private var anchorManager:WindowManager?=null
    private data class Overlay(val id:Int,val manager:WindowManager,val view:HingeSceneView,
        val size:PanelSize,val inner:Boolean,val born:Long)
    private fun size(d:Display):PanelSize { val p=Point(); d.getRealSize(p); return PanelSize(p.x,p.y,d.rotation) }
    private var lastRenderTick=0L
    @Volatile private var uiTick=0L
    @Volatile private var guardActive=false
    private val uiGuard=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private fun fatal(message:String) {
        note("FATAL $message; removing app-owned windows by process exit")
        probe?.close()
        // Process death removes all accessibility windows, even an unattached ViewRoot.
        // Shell lease/EOF independently releases the display request.
        android.os.Process.killProcess(android.os.Process.myPid())
    }
    private fun removeWindow(id:Int) { try { windows.drop(id) } catch(e:Exception) { fatal("remove ${e.javaClass.simpleName}") } }
    private fun clearWindows() { windows.entries().keys.forEach(::removeWindow) }
    private val receiver=object:BroadcastReceiver() {
        override fun onReceive(c:Context,i:Intent) {
            if(i.action==Intent.ACTION_SCREEN_OFF) suspendSession("Приостановлено: экран выключен")
            else scheduleResume()
        }
    }
    private fun displayChanged() {
        val primary=screens.getDisplay(Display.DEFAULT_DISPLAY)?.let(::size)
        if(primary!=primaryShape) {
            primaryShape=primary; primaryStableAfter=SystemClock.elapsedRealtime()+650
            capturesRemaining=2; captureGeneration++; capturePending=false
            gestureValidated=false; windowCaptureBlocked=false
            note("PRIMARY geometry=$primary; refresh native source")
        }
        // The cover merely turning on must not tear down the inner animation.
        windows.entries().forEach { (id,w) ->
            val d=screens.getDisplay(id)
            if(d==null || d.state!=Display.STATE_ON || size(d)!=w.size) removeWindow(id)
        }
    }
    private val displayListener=object:DisplayManager.DisplayListener {
        override fun onDisplayAdded(id:Int) { displayChanged() }
        override fun onDisplayRemoved(id:Int) { displayChanged() }
        override fun onDisplayChanged(id:Int) { displayChanged() }
    }
    override fun onServiceConnected() {
        if(Build.VERSION.SDK_INT<33) { status="Нужен Android 13 или новее"; return }
        instance=this
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("animation","Анимация Hinge Lab",NotificationManager.IMPORTANCE_LOW))
        registerReceiver(receiver,IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) },RECEIVER_NOT_EXPORTED)
        screens.registerDisplayListener(displayListener,handler)
        displayChanged()
        // Reconnection never silently restarts screen overrides after a service crash/reboot.
        getSharedPreferences("everywhere",0).edit().remove("mode").apply()
        status="Готово к включению"
        uiGuard.scheduleWithFixedDelay({
            if(guardActive && SystemClock.elapsedRealtime()-uiTick>3000) {
                android.util.Log.e("HingeEverywhere","UI watchdog: process exit")
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        },500,500,java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    private val uiPulse=object:Runnable {
        override fun run() {
            if(!guardActive || probe==null) return
            val now=SystemClock.elapsedRealtime()
            uiTick=now; probe?.uiAlive=now
            // Background accessibility windows may temporarily lose vsync after unlock.
            // A live main looper must not be mistaken for a frozen UI.
            if(now-lastRenderTick>=250) {
                Choreographer.getInstance().removeFrameCallback(frame)
                frame.doFrame(System.nanoTime())
            }
            if(guardActive && probe!=null) handler.postDelayed(this,100)
        }
    }
    private val testStop=Runnable { note("TEST timeout 45s"); disable() }
    fun enable(selected:Int, boundedTest:Boolean=false, panelDiagnostic:Boolean=false, fallbackExperiment:Int=0) {
        handler.removeCallbacks(testStop)
        require(selected in setOf(71,86,87,88))
        suspendSession("Подготовка")
        require(fallbackExperiment in 0..2 && (fallbackExperiment==0 || boundedTest || (selected==86 && fallbackExperiment==2 && !panelDiagnostic)))
        this.fallbackExperiment=fallbackExperiment; transferGate=TransferCycleGate(); transferUntil=0; restoredAt=0; focusQuery=0; pendingFocus?.invoke("cancel"); pendingFocus=null
        this.panelDiagnostic=panelDiagnostic; diagnosticRounds=0; diagnosticAt=0; markerStarted=0; markerHidden=false
        if(panelDiagnostic) PanelProbeResults.clear()
        mode=selected; reconnects=0; retryAfter=0
        handler.removeCallbacks(supervise); handler.postDelayed(supervise,2000)
        journal.clear(); note("START mode=$mode; boundedTest=$boundedTest; fallback=$fallbackExperiment; original display policy, persistent lease")
        if(boundedTest) { filesDir.resolve("controlled-test-start").writeText(SystemClock.elapsedRealtime().toString()); handler.postDelayed(testStop,45000) }
        getSharedPreferences("everywhere",0).edit().putInt("mode",mode).apply()
        lab=true
        notification("Выбрано: ${name(mode)}. Выйди из лаборатории.")
    }
    fun pauseForLab() { lab=true; if(probe!=null) suspendSession("Пауза в лаборатории") }
    fun verifyWindowCleanup() {
        if(probe!=null) { note("OWNERSHIP skipped: active session"); return }
        val d=screens.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val c=createDisplayContext(d).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,null)
        val wm=c.getSystemService(WindowManager::class.java)
        val p=params().apply { width=1; height=1; title="Hinge Lab ownership probe" }
        val first=View(c)
        val owned=OwnedOverlays<Int,View> { wm.removeViewImmediate(it) }
        try {
            owned.add(0,first); wm.addView(first,p)
            val attached=first.isAttachedToWindow
            owned.drop(0)
            note("OWNERSHIP immediate remove; wasAttached=$attached")
            val second=View(c)
            owned.add(1,second); wm.addView(second,p)
            handler.postDelayed({
                try {
                    owned.clear()
                    note("OWNERSHIP delayed remove; registryEmpty=${owned.isEmpty()}")
                    android.widget.Toast.makeText(this,"Проверка удаления окон завершена",android.widget.Toast.LENGTH_SHORT).show()
                } catch(e:Exception) { fatal("ownership check ${e.javaClass.simpleName}") }
            },250)
        } catch(e:Exception) { runCatching { owned.clear() }; note("OWNERSHIP failure ${e.javaClass.simpleName}") }
    }
    private val supervise=object:Runnable {
        override fun run() {
            if(mode==0) return
            if(probe==null && !lab && power.isInteractive && !lock.isKeyguardLocked && SystemClock.elapsedRealtime()>=retryAfter) resumeSession()
            handler.postDelayed(this,2000)
        }
    }
    private val resumeTask=Runnable { resumeSession() }
    private fun scheduleResume() { handler.removeCallbacks(resumeTask); handler.postDelayed(resumeTask,1200) }
    private fun resumeSession() {
        if(SystemClock.elapsedRealtime()<retryAfter || mode==0 || lab || !power.isInteractive || lock.isKeyguardLocked || probe!=null) return
        if(!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) { disable(); return }
        if(Build.VERSION.SDK_INT<33) { disable(); return }
        val token=++generation
        transferGate=TransferCycleGate(); transferUntil=0; restoredAt=0; focusQuery=0; pendingFocus?.invoke("cancel"); pendingFocus=null
        received=0; ready=false; moved=0; base=-1; capturesRemaining=2; moving=false
        uiTick=SystemClock.elapsedRealtime(); guardActive=true
        addAnchor()
        val connection=EverywhereProbe(this,mode,{ line -> handler.post {
            if(token==generation) onLine(line)
        } },{ reason,retryable -> handler.post {
            if(token==generation) {
                if(retryable && reconnects<3) {
                    reconnects++
                    suspendSession("Связь потеряна; повторное подключение $reconnects/3")
                    retryAfter=SystemClock.elapsedRealtime()+5000L*reconnects
                } else { disable(); status=reason; notification(reason,true) }
            }
        } })
        connection.earlyEntry=fallbackExperiment==2
        probe=connection
        handler.removeCallbacks(uiPulse); handler.post(uiPulse)
        Choreographer.getInstance().removeFrameCallback(frame)
        Choreographer.getInstance().postFrameCallback(frame)
        Thread({ connection.run() },"hingelab-everywhere").start()
        notification("Ожидаем свежий угол · ${name(mode)}")
    }
    private fun onLine(line:String) {
        if(line.startsWith("FOCUS ")) {
            note(line)
            val fields=line.split(' ').drop(1).mapNotNull { part -> part.split('=',limit=2).takeIf { it.size==2 }?.let { it[0] to it[1] } }.toMap()
            if(fields["query"]?.toIntOrNull()==pendingFocusId) {
                val callback=pendingFocus; pendingFocus=null
                callback?.invoke(fields["package"]?.takeIf { fields["display"]=="0" && it!="unknown" })
            }
            return
        }
        if(line.startsWith("TRANSFER ")) {
            note(line)
            if(line.startsWith("TRANSFER MOVED")) {
                val token=generation
                handler.postDelayed({ if(token==generation && probe!=null && SystemClock.elapsedRealtime()<transferUntil) captureTransferredCover() },maxOf(180L,400L-(SystemClock.elapsedRealtime()-captureAt)))
            }
            if(TransferResultPolicy.safeCompletion(line)) { transferGate.restored(); transferUntil=0; restoredAt=SystemClock.elapsedRealtime() }
            if(TransferResultPolicy.failedCompletion(line)) disable()
        }
        if(line.startsWith("DISPLAY ") || line.startsWith("META ")) {
            note(line)
            if(panelDiagnostic) PanelProbeResults.event("${SystemClock.elapsedRealtime()} $line")
        }
        if(line=="DISPLAY ready") { ready=true }
        if(line=="DISPLAY idle") {
            ready=false
            windows.entries().keys.filter { it!=Display.DEFAULT_DISPLAY }.forEach(::removeWindow)
        }
        if(line.startsWith("DISPLAY posture ")) {
            base=line.substringAfterLast(' ').toIntOrNull() ?: -1
            if(base==0) { transferGate.boundary(); clearWindows(); capturesRemaining=2; primaryStableAfter=SystemClock.elapsedRealtime()+120 }
        }
        if(line.startsWith("ANGLE ")) {
            val value=line.removePrefix("ANGLE ").substringBefore(' ').toFloatOrNull() ?: return
            if(!value.isFinite() || value !in 0f..180f) return
            val now=SystemClock.elapsedRealtime()
            if(abs(value-angle)>=1f) {
                closingMotion=value<angle
                moved=now
            }
            if(received==0L) shown=value
            if(value>=178f) transferGate.boundary()
            angle=value; received=now
            if(now-lastSampleLog>=2000) { lastSampleLog=now; note("ANGLE $value") }
            if(status.startsWith("Ожидаем")) notification("Активно · ${name(mode)}")
        }
    }
    override fun onAccessibilityEvent(event:AccessibilityEvent?) = handleAccessibilityEvent(event,false)
    private fun handleAccessibilityEvent(event:AccessibilityEvent?,focusConfirmed:Boolean) {
        val pkg=event?.packageName?.toString() ?: return
        if(fallbackExperiment==2 && SystemClock.elapsedRealtime()<transferUntil && !lock.isKeyguardLocked) return
        if(pkg==packageName) {
            if(event.className?.toString()?.endsWith("Activity")==true) pauseForLab()
            return
        }
        if(lock.isKeyguardLocked) { suspendSession("Пауза на экране блокировки"); return }
        if(event.eventType!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if(pkg==foreground && event.displayId==Display.DEFAULT_DISPLAY && probe!=null) {
                val now=SystemClock.elapsedRealtime()
                // Content events carry no view-tree access. Invalidate both native layouts:
                // neither a previous scroll position nor a previous screen is current.
                if(!moving && now>=primaryStableAfter && now-lastContentRefresh>=250) {
                    lastContentRefresh=now; contentEpoch++; invalidateFrames()
                    capturesRemaining=2; primaryStableAfter=maxOf(primaryStableAfter,now+150)
                    note("CONTENT changed; fresh capture required")
                }
            }
            return
        }
        // Notification updates from this service are not a foreground app switch.
        if(pkg=="com.android.systemui") {
            val type=event.className?.toString().orEmpty()
            if(type.contains("NotificationShade") || type.contains("Keyguard")) {
                clearWindows(); nativeFrames.clear(); gestureValidated=false
            }
            return
        }
        if(pkg!=foreground && !focusConfirmed && fallbackExperiment==2 && probe!=null &&
            restoredAt>0 && SystemClock.elapsedRealtime()-restoredAt in 0..1500 &&
            ForegroundEventPolicy.establishesOwner(event.displayId,event.isFullScreen)) {
            pendingFocus?.invoke("cancel")
            val copy=AccessibilityEvent.obtain(event)
            val token=generation; val epoch=contentEpoch; val connection=probe!!
            val query=++focusQuery; pendingFocusId=query
            pendingFocus={ actual ->
                try {
                    if(actual!="cancel" && token==generation && probe===connection && epoch==contentEpoch && SystemClock.elapsedRealtime()>=transferUntil) {
                        note("APP verified candidate=$pkg actual=$actual previous=$foreground")
                        when(ForegroundVerification.decide(actual,pkg,foreground)) {
                            1 -> handleAccessibilityEvent(copy,true)
                            2 -> invalidateFrames()
                        }
                    }
                } finally { copy.recycle() }
            }
            connection.requestFocusCheck()
            handler.postDelayed({
                if(token==generation && probe===connection && pendingFocusId==query) { val callback=pendingFocus; pendingFocus=null; callback?.invoke(null) }
            },600)
            return
        }
        if(pkg!=foreground) {
            if(!ForegroundEventPolicy.establishesOwner(event.displayId,event.isFullScreen)) {
                note("APP ignored pkg=$pkg class=${event.className} display=${event.displayId} full=${event.isFullScreen}")
                return
            }
            foreground=pkg; contentEpoch++; invalidateFrames(); capturesRemaining=2
            primaryStableAfter=SystemClock.elapsedRealtime()+350
            primaryWindowId=-1; windowCaptureBlocked=false
            note("APP epoch=$contentEpoch pkg=$pkg class=${event.className}")
        }
        if(event.windowId>=0 && (Build.VERSION.SDK_INT<33 || event.displayId==Display.DEFAULT_DISPLAY)) {
            if(primaryWindowId!=event.windowId) {
                primaryWindowId=event.windowId; windowCaptureBlocked=false
                capturesRemaining=2
                note("WINDOW id=$primaryWindowId")
            }
        }
        if(lab) { lab=false; scheduleResume() }
    }
    private val frame=object:Choreographer.FrameCallback {
        override fun doFrame(nanos:Long) {
            val now=SystemClock.elapsedRealtime(); lastRenderTick=now
            nativeFrames.expire(now)
            val dt=if(lastFrame==0L) .016f else ((nanos-lastFrame)/1e9f).coerceIn(0f,.05f)
            lastFrame=nanos
            if(probe!=null) {
                if(!power.isInteractive || lock.isKeyguardLocked) suspendSession("Пауза: экран выключен или заблокирован")
                else {
                    probe?.uiAlive=now
                    if(panelDiagnostic) {
                        if(ready && markerStarted==0L) showDiagnosticMarker(now)
                        if(!ready && markerStarted!=0L) { removeDiagnosticMarker(); diagnosticRounds=6 }
                        if(ready && markerStarted!=0L && now-markerStarted>=2500 && !markerHidden) hideDiagnosticMarker(now)
                        if(ready && markerHidden && diagnosticRounds<6 && now-diagnosticAt>=650) captureDiagnostic(now)
                        if(diagnosticRounds>=6) removeDiagnosticMarker()
                        Choreographer.getInstance().postFrameCallbackDelayed(this,32)
                        return
                    }
                    val fresh=AngleData.fresh(received,now)
                    val motion=fresh && now-moved<=700 && angle<(if(fallbackExperiment==2) 179f else 178f) && angle>1f && base!=0
                    if(motion && !moving) {
                        exhausted.clear(); missing.clear()
                        // A just-primed native frame remains valid when the first angle
                        // arrives. Do not add another capture interval before showing it.
                        gestureValidated=primaryShape?.let { nativeFrames.get(it,now,contentEpoch)!=null }==true
                        capturesRemaining=maxOf(capturesRemaining,1)
                    }
                    moving=motion
                    shown+=((if(fallbackExperiment==2 && fresh && angle<179f) angle else DemoMotion.target(angle,true,fresh))-shown)*(1f-exp(-dt/.045f))
                    if(!motion) { clearWindows(); exhausted.clear() }
                    else {
                        windows.entries().forEach { (id,w) ->
                            if(now-w.born>=3500) {
                                exhausted.add(id); removeWindow(id); note("OVERLAY hard expiry display=$id")
                            } else w.view.tilt=HingeProjection.tiltFor(shown,w.view.config,w.inner)
                        }
                        renderAvailable(now)
                    }
                    // Prime native closed/open layouts once, not by continuous idle capture.
                    if(!capturePending && capturesRemaining>0 && now-captureAt>=400 && now>=primaryStableAfter) captureNative()
                }
            }
            if(probe!=null) Choreographer.getInstance().postFrameCallbackDelayed(this,if(windows.isEmpty()) 32 else 0)
        }
    }
    private fun markerParams(full:Boolean)=params().apply {
        flags=(flags and WindowManager.LayoutParams.FLAG_SECURE.inv()) or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        title="Hinge Lab external panel probe"
        if(!full) { width=1; height=1; gravity=Gravity.TOP or Gravity.LEFT }
    }
    private fun showDiagnosticMarker(now:Long) {
        val display=screens.getDisplay(1) ?: return
        markerStarted=now
        val c=createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,null)
        val wm=c.getSystemService(WindowManager::class.java)
        val view=android.widget.TextView(c).apply {
            text="ВНЕШНИЙ ЭКРАН\nHinge Lab\nПлашка исчезнет через 2,5 секунды"
            gravity=Gravity.CENTER; textSize=25f; setTextColor(Color.BLACK); setBackgroundColor(Color.CYAN)
        }
        markerView=view; markerManager=wm
        try {
            wm.addView(view,markerParams(true))
            PanelProbeResults.event("Плашка добавлена на display=1; видимость подтверждает пользователь")
            note("PANEL_PROBE marker_added")
            view.viewTreeObserver.registerFrameCommitCallback { note("PANEL_PROBE marker_frame_committed") }
            val token=generation
            handler.postDelayed({ if(token==generation) { removeDiagnosticMarker(); diagnosticRounds=6 } },8500)
        } catch(e:Exception) {
            PanelProbeResults.event("Плашка: ${e.javaClass.simpleName}")
            removeDiagnosticMarker(); diagnosticRounds=6
        }
    }
    private fun hideDiagnosticMarker(now:Long) {
        val view=markerView ?: return
        try {
            // Keep only a transparent 1px window while sampling the underlying content.
            // It requests screen-on but neither moves nor duplicates application tasks.
            view.text=""; view.setBackgroundColor(Color.TRANSPARENT)
            markerManager?.updateViewLayout(view,markerParams(false))
            markerHidden=true; diagnosticAt=now
            PanelProbeResults.event("Плашка убрана; прозрачное окно 1px удерживает экран на время снимков")
            note("PANEL_PROBE marker_hidden; 1px keep-screen-on")
        } catch(e:Exception) { removeDiagnosticMarker(); diagnosticRounds=6 }
    }
    private fun removeDiagnosticMarker() {
        val view=markerView ?: return
        try { markerManager?.removeViewImmediate(view) }
        catch(_:IllegalArgumentException) { }
        catch(e:Exception) { fatal("marker removal ${e.javaClass.simpleName}"); return }
        markerView=null; markerManager=null
        note("PANEL_PROBE marker_removed")
    }
    private fun captureDiagnostic(now:Long) {
        diagnosticAt=now; diagnosticRounds++
        val round=(diagnosticRounds+1)/2; val token=generation
        val angleAtCapture=angle
        // Screenshot throttling is shared across displays. One request per tick.
        listOfNotNull(screens.getDisplay(if(diagnosticRounds%2==1) 0 else 1)).forEach { display ->
            val caption="Раунд $round · display=${display.displayId} · ${size(display)} · state=${display.state} · угол=$angleAtCapture · $now"
            PanelProbeResults.event(caption)
            if(display.state!=Display.STATE_ON) return@forEach
            try { takeScreenshot(display.displayId,mainExecutor,object:TakeScreenshotCallback {
                override fun onSuccess(result:ScreenshotResult) {
                    val buffer=result.hardwareBuffer
                    val bitmap=try {
                        Bitmap.wrapHardwareBuffer(buffer,result.colorSpace)?.let { original ->
                            try {
                                val scale=600f/maxOf(original.width,original.height)
                                Bitmap.createScaledBitmap(original,maxOf(1,(original.width*scale).toInt()),maxOf(1,(original.height*scale).toInt()),true).let { scaled ->
                                    try { scaled.copy(Bitmap.Config.ARGB_8888,false) } finally { if(scaled!==original) scaled.recycle() }
                                }
                            } finally { original.recycle() }
                        }
                    } finally { buffer.close() }
                    if(token==generation && panelDiagnostic && bitmap!=null) {
                        PanelProbeResults.frames.add(caption to bitmap)
                        note("PANEL_PROBE captured display=${display.displayId} round=$round")
                    }
                }
                override fun onFailure(code:Int) { if(token==generation) PanelProbeResults.event("$caption · ошибка снимка $code") }
            }) } catch(e:Exception) { PanelProbeResults.event("$caption · ${e.javaClass.simpleName}") }
        }
    }
    private fun captureNative() {
        if(SystemClock.elapsedRealtime()<transferUntil) return
        val d=screens.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        if(d.state!=Display.STATE_ON || foreground.isEmpty()) return
        val shape=size(d)
        val useWindow=Build.VERSION.SDK_INT>=34 && primaryWindowId>=0 && !windowCaptureBlocked
        // Display capture is only allowed with no app-owned picture on that display.
        if(!useWindow && windows.get(d.displayId)!=null) return
        if(shape.width<2 || shape.height<2) return
        capturesRemaining--; capturePending=true; captureAt=SystemClock.elapsedRealtime()
        val token=++captureGeneration; val epoch=contentEpoch; val windowId=primaryWindowId
        handler.postDelayed({ if(token==captureGeneration && capturePending) {
            captureGeneration++; capturePending=false; note("CAPTURE timeout")
        } },1000)
        val callback=object:TakeScreenshotCallback {
            override fun onSuccess(result:ScreenshotResult) {
                val buffer=result.hardwareBuffer
                val shot=try {
                    Bitmap.wrapHardwareBuffer(buffer,result.colorSpace)?.let { hardware ->
                        try { hardware.copy(Bitmap.Config.ARGB_8888,false) } finally { hardware.recycle() }
                    }
                } catch(_:Exception) { null } finally { buffer.close() }
                if(token!=captureGeneration) return
                capturePending=false
                if(shot==null || probe==null || lab || lock.isKeyguardLocked || epoch!=contentEpoch || size(d)!=shape || d.state!=Display.STATE_ON) return
                if(shot.width!=shape.width || shot.height!=shape.height) {
                    note("CAPTURE rejected dimensions ${shot.width}x${shot.height} expected=$shape")
                    if(useWindow) { windowCaptureBlocked=true; capturesRemaining=maxOf(capturesRemaining,1) }
                    return
                }
                val pixels=IntArray(256) { i -> shot.getPixel((i%16)*(shot.width-1)/15,(i/16)*(shot.height-1)/15) }
                if(!NativeFrameQuality.usable(pixels)) {
                    nativeFrames.remove(shape)
                    windows.entries().filterValues { it.size==shape }.keys.forEach(::removeWindow)
                    note("CAPTURE rejected near-black native=$shape; other panel retained")
                    return
                }
                val now=SystemClock.elapsedRealtime()
                nativeFrames.put(shape,shot,now,epoch); gestureValidated=true
                note("CAPTURE owner=$foreground epoch=$epoch native=$shape via=${if(useWindow) "window:$windowId" else "display"}")
                // Refresh the texture in place; never recreate a valid window on DISPLAY ready.
                windows.entries().values.filter { it.size==shape }.forEach { it.view.updateSnapshot(shot) }
                if(moving) renderAvailable(now)
            }
            override fun onFailure(errorCode:Int) {
                if(token!=captureGeneration) return
                capturePending=false
                note("CAPTURE failed code=$errorCode viaWindow=$useWindow")
                if(errorCode==ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                    nativeFrames.clear(); clearWindows(); gestureValidated=false
                } else if(useWindow) { windowCaptureBlocked=true; capturesRemaining=maxOf(capturesRemaining,1) }
            }
        }
        try {
            if(useWindow && Build.VERSION.SDK_INT>=34) takeScreenshotOfWindow(windowId,mainExecutor,callback)
            else takeScreenshot(d.displayId,mainExecutor,callback)
        } catch(e:Exception) { capturePending=false; note("CAPTURE ${e.javaClass.simpleName}") }
    }
    private fun cropCover(source:Bitmap,target:PanelSize):Bitmap {
        val config=EffectProfile()
        val axis=HingeProjection.foldFor(true,source.width.toFloat(),source.height.toFloat(),config)
        val rect=RectF(0f,0f,source.width.toFloat(),source.height.toFloat())
        if(axis.splitsX) rect.right=rect.width()/2 else rect.bottom=rect.height()/2
        val ratio=target.width.toFloat()/target.height
        if(rect.width()/rect.height()>ratio) {
            val trim=(rect.width()-rect.height()*ratio)/2; rect.left+=trim; rect.right-=trim
        } else { val trim=(rect.height()-rect.width()/ratio)/2; rect.top+=trim; rect.bottom-=trim }
        val output=Bitmap.createBitmap(target.width,target.height,Bitmap.Config.ARGB_8888)
        val matrix=Matrix().apply { setRectToRect(rect,RectF(0f,0f,target.width.toFloat(),target.height.toFloat()),Matrix.ScaleToFit.FILL) }
        Canvas(output).drawBitmap(source,matrix,Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }
    private fun captureTransferredCover() {
        val requestProbe=probe ?: return
        val display=screens.getDisplay(1) ?: return
        if(display.state!=Display.STATE_ON) { note("TRANSFER capture display off"); return }
        val token=generation; val epoch=contentEpoch; val shape=size(display)
        try { takeScreenshot(1,mainExecutor,object:TakeScreenshotCallback {
            override fun onSuccess(result:ScreenshotResult) {
                Thread({ runCatching { requestProbe.restoreTransfer() } },"restore-after-capture").start()
                val buffer=result.hardwareBuffer
                val shot=try { Bitmap.wrapHardwareBuffer(buffer,result.colorSpace)?.let { image ->
                    try { image.copy(Bitmap.Config.ARGB_8888,false) } finally { image.recycle() }
                } } finally { buffer.close() }
                if(token!=generation || epoch!=contentEpoch || shot==null || shot.width!=shape.width || shot.height!=shape.height) return
                val pixels=IntArray(256) { i -> shot.getPixel((i%16)*(shot.width-1)/15,(i/16)*(shot.height-1)/15) }
                if(!NativeFrameQuality.usable(pixels)) { note("TRANSFER capture blank; skipped"); return }
                nativeFrames.put(shape,shot,SystemClock.elapsedRealtime(),epoch)
                note("TRANSFER captured native cover; visual validation required")
                if(moving) renderAvailable(SystemClock.elapsedRealtime())
            }
            override fun onFailure(code:Int) { Thread({ runCatching { requestProbe.restoreTransfer() } },"restore-after-failure").start(); note("TRANSFER screenshot error=$code") }
        }) } catch(e:Exception) { note("TRANSFER capture ${e.javaClass.simpleName}") }
    }
    private fun renderAvailable(now:Long) {
        screens.displays.filter { it.state==Display.STATE_ON && (ready || it.displayId==0) && it.displayId !in exhausted }.forEach { d ->
            if(windows.get(d.displayId)!=null) return@forEach
            val shape=size(d)
            val shot=nativeFrames.get(shape,now,contentEpoch)
            if(shot==null) {
                if(FallbackPolicy.allowed(fallbackExperiment,false,closingMotion,ready,d.displayId)) {
                    val primary=screens.getDisplay(0)
                    val source=primary?.let { nativeFrames.get(size(it),now,contentEpoch) }
                    if(source!=null && fallbackExperiment==1) {
                        show(d,cropCover(source,shape),shape,now)
                        note("FALLBACK cropped inner; native cover absent")
                        return@forEach
                    }
                    if(source!=null && fallbackExperiment==2 && transferGate.begin()) {
                        transferUntil=now+3000
                        Thread({ runCatching { probe?.requestTransfer() }.onFailure { android.util.Log.w("HingeEverywhere","Transfer request failed",it) } },"request-task-transfer").start()
                        note("FALLBACK requested temporary transfer; native cover absent")
                    }
                }
                if(missing.add(shape)) note("FRAME missing native=$shape; effect skipped")
            } else show(d,shot,shape,now)
        }
    }
    private fun show(d:Display,bitmap:Bitmap,shape:PanelSize,now:Long) {
        if(windows.get(d.displayId)!=null) return
        val context=createDisplayContext(d).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,null)
        val inner=minOf(shape.width,shape.height)/context.resources.displayMetrics.density>=600f
        val scene=HingeSceneView(context,bitmap,{ w,h,c->HingeProjection.foldFor(inner,w,h,c) }).apply {
            config=EffectProfile(movingSide=if(d.rotation>=2) 1 else -1,
                perspectiveWindow=getSharedPreferences("everywhere",0).getBoolean("perspective_window",false))
            tilt=HingeProjection.tiltFor(shown,config,inner)
        }
        val wm=context.getSystemService(WindowManager::class.java)
        // Take ownership before addView: attachment callbacks are asynchronous.
        if(!windows.add(d.displayId,Overlay(d.displayId,wm,scene,shape,inner,now))) { scene.release(); return }
        try {
            wm.addView(scene,params()); note("OVERLAY attached display=${d.displayId} native=$shape")
            handler.postDelayed({
                if(windows.get(d.displayId)?.view===scene) {
                    exhausted.add(d.displayId); removeWindow(d.displayId); note("OVERLAY deadline display=${d.displayId}")
                }
            },3500)
        } catch(e:Exception) { removeWindow(d.displayId); note("OVERLAY failed ${e.javaClass.simpleName}") }
    }
    private fun params()=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT).apply { title="Hinge Lab animation"; layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS; setFitInsetsTypes(0) }
    private fun addAnchor() {
        // The existing wallpaper source requires visibility. No wallpaper is replaced.
        try {
            val c=createDisplayContext(screens.getDisplay(Display.DEFAULT_DISPLAY)).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,null)
            val wm=c.getSystemService(WindowManager::class.java)
            val view=View(c)
            val p=params().apply { flags=(flags and WindowManager.LayoutParams.FLAG_SECURE.inv()) or WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER; title="Hinge Lab source" }
            wm.addView(view,p); anchor=view; anchorManager=wm
        } catch(e:Exception) { note("SOURCE window failed ${e.javaClass.simpleName}") }
    }
    private fun invalidateFrames() {
        captureGeneration++; capturePending=false; nativeFrames.clear(); clearWindows()
        moving=false; gestureValidated=false; exhausted.clear(); missing.clear()
    }
    private fun suspendSession(message:String) {
        if(probe==null && anchor==null && !guardActive) return
        note(message)
        pendingFocus?.invoke("cancel"); pendingFocus=null
        handler.removeCallbacks(uiPulse)
        removeDiagnosticMarker()
        guardActive=false; generation++; probe?.close(); probe=null; received=0; ready=false
        Choreographer.getInstance().removeFrameCallback(frame)
        invalidateFrames()
        anchor?.let { runCatching { anchorManager?.removeViewImmediate(it) } }; anchor=null; anchorManager=null
        if(mode!=0) notification(message)
    }
    fun disable() {
        mode=0; handler.removeCallbacks(supervise); handler.removeCallbacks(testStop); handler.removeCallbacks(resumeTask); suspendSession("Выключено")
        getSharedPreferences("everywhere",0).edit().remove("mode").apply()
        status="Выключено"; getSystemService(NotificationManager::class.java).cancel(1086)
    }
    private fun notification(message:String,failed:Boolean=false) {
        status=message
        val open=PendingIntent.getActivity(this,0,Intent(this,EverywhereSettingsActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getBroadcast(this,0,Intent(this,StopAnimationReceiver::class.java),PendingIntent.FLAG_IMMUTABLE)
        val n=Notification.Builder(this,"animation").setSmallIcon(R.drawable.ic_launcher).setContentTitle("Hinge Lab · ${if(failed) "остановлено" else "анимация"}")
            .setContentText(message).setContentIntent(open).setOngoing(!failed).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null,"Остановить",stop).build()).build()
        getSystemService(NotificationManager::class.java).notify(1086,n)
    }
    override fun onInterrupt() { disable() }
    override fun onDestroy() {
        disable(); instance=null; uiGuard.shutdownNow()
        Choreographer.getInstance().removeFrameCallback(frame)
        runCatching { unregisterReceiver(receiver) }; screens.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }
}

class StopAnimationReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) { EverywhereAnimationService.stop(context) }
}
