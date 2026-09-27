package dev.duohome.hingelab

import android.app.Activity
import android.os.*
import android.content.res.Configuration
import android.graphics.*
import android.view.*
import android.widget.*
import dev.duohome.hingelab.effect.*
import kotlin.math.exp

/** Generated texture; optional bounded dual-display experiment through the shell helper. */
class DemoActivity : Activity() {
    private val dual by lazy { intent.getBooleanExtra("dualDisplay",false) }
    private val experimentMode by lazy { intent.getIntExtra("experimentMode",69) }
    private val rawWindow get()=dual && experimentMode==88
    private val frameContinuity get()=dual && (experimentMode==87 || experimentMode==88)
    private val constantCover get()=dual && experimentMode==85
    private var primaryTaps=0
    private var secondaryTaps=0
    private val rawDisplayListener=object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) { refreshRawDisplay() }
        override fun onDisplayChanged(id: Int) { refreshRawDisplay() }
        override fun onDisplayRemoved(id: Int) { refreshRawDisplay() }
    }
    private fun refreshRawDisplay() {
        if(rawWindow && probe!=null && !dualEnded && resumed) updateSecondary()
    }
    private val zfold get()=dual && experimentMode==70
    private val tent get()=dual && (experimentMode==77 || experimentMode==79 || experimentMode==82 || experimentMode==83 || experimentMode==84 || experimentMode==85 || experimentMode==86 || experimentMode==87 || experimentMode==88)
    private val recordSession get()=zfold || tent || experimentMode==81
    private val softInner by lazy { intent.getBooleanExtra("softInner",false) }
    private val compatFrames get()=dual && experimentMode==72
    private val earlyInner by lazy { intent.getBooleanExtra("earlyInner",false) }
    private val boundedInner by lazy { intent.getBooleanExtra("boundedInner",false) }
    private fun projection(angle: Float, config: EffectProfile, inner: Boolean): Float =
        if(softInner && inner) DemoMotion.softInnerTilt(angle)
        else if(boundedInner && inner) DemoMotion.boundedInnerTilt(angle)
        else if(earlyInner && inner) (DemoMotion.earlyInnerTilt(angle)*config.intensity).coerceIn(0f,HingeProjection.MAX_TILT)
        else HingeProjection.tiltFor(angle,config,inner)
    private val panelFrames by lazy { CompatPanelFrames { inner, tilt ->
        if(renderedInner==inner) scene?.tilt=tilt
        if(secondaryInner==inner) secondaryScene?.tilt=tilt
    } }
    private var responses=0
    private val distinct=sortedSetOf<Float>()
    private var wakeCount=0
    private var zfoldStep="Ожидаем запуск"
    private val zfoldJournal=ArrayDeque<String>()
    private var dualReady=false
    private var dualLayout=4
    private var preparingHandoff=false
    private var frameRequest: String?=null
    private var userLeaving=false
    private var handoffMask=false
    private var secondaryInner=false
    private var secondarySize=""
    private var dualBegan=0L
    private var secondary: android.app.Dialog?=null
    private var secondaryScene: HingeSceneView?=null
    private var secondaryStatus: TextView?=null
    private var secondaryId=-1
    private var displayCheckAt=0L
    private var dualMessage="Готовим два экрана…"
    private var dualEnded=false
    private var dualError=false
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var stage: FrameLayout
    private lateinit var status: TextView
    private lateinit var controls: LinearLayout
    private var scene: HingeSceneView?=null
    private var renderedWidth=0
    private var renderedHeight=0
    private var renderedInner=false
    private fun innerPanel(): Boolean {
        if(zfold && handoffMask) return false
        if(dualReady && !compatFrames && !tent) return dualLayout==4
        val size=Point(); display?.getRealSize(size)
        return minOf(size.x,size.y)/resources.displayMetrics.density>=600f
    }
    private val rebuildTask=Runnable { rebuild() }
    private fun scheduleRebuild() {
        stage.removeCallbacks(rebuildTask)
        stage.post(rebuildTask)
    }
    private var probe: ShellAngleProbe?=null
    private var previewAnimator: android.animation.ValueAnimator?=null
    private var movingSide=-1
    private var live=true
    private var received=0L
    private var target=180f
    private var displayed=180f
    private var lastFrame=0L
    private var resumed=false
    private var generation=0
    private var connectionStatus="Ожидаем помощник"
    private val frame=object: Choreographer.FrameCallback {
        override fun doFrame(time: Long) {
            if(!resumed && !((zfold || compatFrames || tent) && probe!=null && !dualEnded)) return
            val dt=if(lastFrame==0L) 0.016f else ((time-lastFrame)/1e9f).coerceIn(0f,.05f)
            lastFrame=time
            val fresh=AngleData.fresh(received,SystemClock.elapsedRealtime())
            val inner=innerPanel()
            val safeTarget=if(live && !fresh) { if(inner) 180f else 0f } else DemoMotion.target(target,live,fresh)
            displayed+=(safeTarget-displayed)*(1f-exp(-dt/0.045f))
            val config=EffectProfile(movingSide=(if((display?.rotation ?: 0)>=2) -1 else 1)*movingSide,boundedInner=boundedInner,softInner=softInner)
            // Mode 82 waits until 150° to power the cover, not to animate the
            // already visible inner display. Keep the stale-data guard below.
            val animateSingleInner=dual && (experimentMode==82 || experimentMode==83 || experimentMode==84 || experimentMode==85 || experimentMode==86 || experimentMode==87 || experimentMode==88) && inner && !dualEnded
            scene?.let { it.config=config; if(!compatFrames) it.tilt=if(constantCover || preparingHandoff || (dual && !dualReady && !zfold && !animateSingleInner) || (live && !fresh)) 0f else projection(displayed,config,inner) }
            if(compatFrames) panelFrames.follow(safeTarget, fresh && dualReady, config)
            if(dualReady) {
                val now=SystemClock.elapsedRealtime()
                if(now-displayCheckAt>50) { displayCheckAt=now; updateSecondary() }
                secondaryScene?.let { it.config=config; if(!compatFrames) it.tilt=if(constantCover || !fresh) 0f else projection(displayed,config,secondaryInner) }
            }
            commitRequestedFrame()
            status.text=if(constantCover) "Постоянный режим 5 · без анимации\nПроверяем экраны и касания; угол не измеряем"
                else if(zfold) "Z Fold Duo · ответ обоев ${target.toInt()}° · ${if(fresh) "ответ получен" else "ответов нет"}\nОтветов $responses · разных ${distinct.size} · пробуждений $wakeCount\n$zfoldStep\nОбновление ответа не доказывает изменение угла."
                else if(!live) "Ручное демо · ${target.toInt()}° · это не показание датчика"
                else if(fresh) "Живой угол ${target.toInt()}° · ${if(inner) "внутренний" else "внешний"} экран"
                else "$connectionStatus · обычный вид, свежего угла нет"
            if(dual && !zfold) status.append("\n"+if(dualReady) "Два экрана · осталось ${((60000-(SystemClock.elapsedRealtime()-dualBegan))/1000).coerceAtLeast(0)} с" else dualMessage)
            if(earlyInner) status.append(if(softInner) "\nВнутренний: мягкий уход за край · 55–178°" else if(boundedInner) "\nВнутренний: содержимое в кадре · 55–180°" else "\nВнутренний: эффект 55–175° · внешний: классический 0.18")
            secondaryStatus?.text=if(constantCover) "Внутренний экран · режим 5 · кадр ${SystemClock.elapsedRealtime()/1000} с" else "${if(secondaryInner) "Внутренний" else "Внешний"} экран · "+if(fresh) "${if(zfold) "ответ обоев" else "живой угол"} ${target.toInt()}°" else "свежего угла нет"
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EverywhereAnimationService.instance?.pauseForLab()
        if(Build.VERSION.SDK_INT<33) { Toast.makeText(this,"Для стеклянного эффекта нужен Android 13 или новее",Toast.LENGTH_LONG).show(); finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // FoldInteractive must stay visible to keep its sensor subscription alive.
        // Our opaque content covers it; removing SHOW_WALLPAPER also loses the angle source.
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        if(frameContinuity) window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xff182133.toInt()))
        if(earlyInner) movingSide=1
        val root=FrameLayout(this)
        stage=FrameLayout(this); root.addView(stage,FrameLayout.LayoutParams(-1,-1))
        controls=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,12,24,12); setBackgroundColor(0xea111820.toInt()) }
        status=TextView(this).apply { setTextColor(Color.WHITE); textSize=15f }; controls.addView(status)
        val mode=Switch(this).apply { text="Следовать настоящему углу"; setTextColor(Color.WHITE); isChecked=true }; controls.addView(mode)
        controls.addView(Spinner(this).apply {
            if(constantCover) visibility=View.GONE
            adapter=ArrayAdapter(this@DemoActivity,android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Эффект: левая половина","Эффект: правая половина","Эффект: обе половины"))
            onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    movingSide=when(position) { 0 -> -1; 1 -> 1; else -> 0 }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            if(earlyInner) setSelection(1)
        })
        if(constantCover) controls.addView(Button(this).apply { text="Проверить касание: 0"; setOnClickListener { primaryTaps++; text="Касаний: $primaryTaps" } })
        val seek=SeekBar(this).apply { max=180; progress=180; visibility=View.GONE }; controls.addView(seek)
        seek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { if(user) target=p.toFloat() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        controls.addView(Button(this).apply {
            text="Показать пример без сгибания"
            if(dual) visibility=View.GONE
            setOnClickListener {
                previewAnimator?.cancel()
                mode.isChecked=false
                previewAnimator=android.animation.ValueAnimator.ofInt(180,100,45,100,180).apply {
                    duration=3200
                    addUpdateListener { if(!live && resumed) seek.progress=it.animatedValue as Int; if(!live && resumed) target=seek.progress.toFloat() }
                    start()
                }
            }
        })
        mode.setOnCheckedChangeListener { _,checked -> previewAnimator?.cancel(); live=checked; seek.visibility=if(checked) View.GONE else View.VISIBLE; if(checked) connect() else { generation++; probe?.close(); probe=null; target=seek.progress.toFloat() } }
        if(dual && experimentMode in listOf(71,86,87,88) && !earlyInner && !compatFrames) controls.addView(Button(this).apply {
            text="Использовать по умолчанию поверх приложений"
            setOnClickListener {
                generation++; probe?.close(); probe=null
                startActivity(android.content.Intent(this@DemoActivity,EverywhereSettingsActivity::class.java).putExtra("mode",experimentMode))
                finish()
            }
        })
        controls.addView(Button(this).apply { text="Назад в лабораторию"; setOnClickListener { finish() } })
        if(recordSession) controls.addView(Button(this).apply {
            text=if(constantCover) "Скопировать результат режима 5" else if(tent) "Скопировать результат раннего раскрытия" else "Скопировать результат Z Fold Duo"
            setOnClickListener {
                val report="Hinge Lab ${packageManager.getPackageInfo(packageName,0).versionName} · режим $experimentMode\nОтветов $responses · разных ${distinct.size} · пробуждений $wakeCount\nДиапазон ${distinct.firstOrNull()} … ${distinct.lastOrNull()}\n"+zfoldJournal.joinToString("\n")
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Z Fold Duo",report))
                Toast.makeText(this@DemoActivity,"Результат скопирован",Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(controls,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        setContentView(root)
        if(rawWindow) getSystemService(android.hardware.display.DisplayManager::class.java).registerDisplayListener(rawDisplayListener,handler)
        if(intent.getBooleanExtra("manualStart",false)) mode.isChecked=false
        if(dual) { mode.visibility=View.GONE }
        stage.addOnLayoutChangeListener { _,l,t,r,b,ol,ot,or,ob -> if(r-l!=or-ol || b-t!=ob-ot) scheduleRebuild() }
        scheduleRebuild()
    }
    private fun rebuild() {
        if(stage.width<2 || stage.height<2) return
        val inner=innerPanel()
        if(scene!=null && renderedWidth==stage.width && renderedHeight==stage.height && renderedInner==inner) return
        renderedInner=inner
        renderedWidth=stage.width; renderedHeight=stage.height
        val previousScene=scene
        val bitmap=if(compatFrames) panelFrames.image(inner,stage.width,stage.height,::texture) else texture(stage.width,stage.height)
        scene=HingeSceneView(this,bitmap,foldLine={ w,h,c -> HingeProjection.foldFor(inner,w,h,c) })
        if(!frameContinuity) { previousScene?.release(); stage.removeAllViews() }
        stage.addView(scene,FrameLayout.LayoutParams(-1,-1))
        if(frameContinuity) {
            window.setBackgroundDrawable(android.graphics.drawable.BitmapDrawable(resources,bitmap))
            val configured=primaryConfigurationChanged
            primaryConfigurationChanged=false
            val retained=secondary
            afterFrame(scene!!) {
                previousScene?.let { stage.removeView(it); it.release() }
                if(configured && !dualReady && secondary===retained && secondaryExpiry!=null) {
                    uiEvent("primary_replacement_committed")
                    clearSecondary()
                }
            }
        }
        scene?.tilt=if(compatFrames) panelFrames.tilt(inner) else 0f
        stage.requestLayout()
        stage.invalidate()
    }
    override fun onConfigurationChanged(config: Configuration) { super.onConfigurationChanged(config); primaryConfigurationChanged=true; window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER); if(!zfold && !compatFrames) { received=0; displayed=if(config.smallestScreenWidthDp>=600) 180f else 0f }; scheduleRebuild(); if(live && resumed && !dual) connect() }
    private fun connect() {
        generation++; val token=generation; probe?.close(); received=0
        if(tent) dualBegan=SystemClock.elapsedRealtime()
        probe=ShellAngleProbe(this,{ a -> handler.post { if(token==generation) { target=a; received=SystemClock.elapsedRealtime(); if(recordSession) { responses++; distinct.add(a) } } } },
            { text -> handler.post { if(token==generation) connectionStatus=text } },{}, {},
            { event -> handler.post {
                if(token==generation && recordSession && (event.startsWith("ZFD ") || event.startsWith("DISPLAY "))) {
                    zfoldJournal.addLast("${SystemClock.elapsedRealtime()} · $event"); while(zfoldJournal.size>80) zfoldJournal.removeFirst()
                    when {
                        event=="ZFD started" -> { dualBegan=SystemClock.elapsedRealtime(); zfoldStep="Ждём изменения угла; второй экран заранее не включаем" }
                        event.startsWith("ZFD wake ") -> { wakeCount=event.substringAfterLast(' ').toIntOrNull() ?: wakeCount; zfoldStep="Отправлено пробуждение №$wakeCount; проверяем ответы" }
                        event=="ZFD unsubscribed" -> zfoldStep="Обои отписались от датчика"
                        event.startsWith("ZFD transition") -> zfoldStep=event.removePrefix("ZFD ")
                        event.startsWith("ZFD error") -> { zfoldStep=event; dualError=true }
                        event=="DISPLAY finished" -> if(!dualError) zfoldStep="Проверка завершена"
                    }
                }
                  if(token==generation && dual) when {
                    event.startsWith("DISPLAY frame ") -> {
                        frameRequest=event.removePrefix("DISPLAY frame ")
                        if(frameRequest=="handoff") {
                            handoffMask=true; preparingHandoff=true
                            controls.visibility=View.INVISIBLE
                            rebuild()
                        }
                    }
                    event=="DISPLAY unmask" -> {
                        handoffMask=false; preparingHandoff=false
                        controls.visibility=View.VISIBLE; scheduleRebuild()
                    }
                    event=="DISPLAY preparing 5" -> { preparingHandoff=true; scene?.tilt=0f }
                    event.startsWith("DISPLAY layout ") -> { dualLayout=event.substringAfterLast(' ').toIntOrNull() ?: 4; if(!handoffMask) preparingHandoff=false; scheduleRebuild(); updateSecondary() }
                    event=="DISPLAY ready" -> { dualReady=true; if(!zfold && dualBegan==0L) dualBegan=SystemClock.elapsedRealtime(); scheduleRebuild(); updateSecondary() }
                    event.startsWith("DISPLAY restored") -> { received=0; scheduleRebuild(); updateSecondary() }
                    event.startsWith("DISPLAY waiting") -> { dualMessage=event.removePrefix("DISPLAY waiting ") }
                    event.startsWith("DISPLAY activating") -> { received=0; dualMessage="Начало раскрытия: включаем внутренний экран" }
                    event=="DISPLAY idle" -> { received=0; dualMessage="Телефон сложен; ждём следующего раскрытия"; dualReady=false; preparingHandoff=false; handoffMask=false; controls.visibility=View.VISIBLE; if(frameContinuity) retainSecondaryBriefly() else clearSecondary(); scheduleRebuild() }
                    event=="DISPLAY finished" -> { dualReady=false; dualEnded=true; handoffMask=false; preparingHandoff=false; controls.visibility=View.VISIBLE; clearSecondary(); probe?.close(); probe=null; if(!dualError) dualMessage="Проверка завершена; возврат обычного режима экранов" }
                    event.startsWith("DISPLAY error") || event.startsWith("DISPLAY cleanup_error") -> { dualError=true; dualMessage=event; connectionStatus=event }
                }
            } },dualDisplay=dual,experimentMode=experimentMode)
        val current=probe; Thread({ current?.run() },"demo-angle").start()
    }
    override fun onStart() { super.onStart(); if(!::stage.isInitialized) return; userLeaving=false; resumed=true; lastFrame=0; if(live && !(dual && dualEnded) && probe==null) connect(); Choreographer.getInstance().removeFrameCallback(frame); Choreographer.getInstance().postFrameCallback(frame) }
    override fun onUserLeaveHint() { userLeaving=true; super.onUserLeaveHint() }
    override fun onStop() {
        previewAnimator?.cancel(); resumed=false
        // A panel handoff must not terminate the source. Explicit Home/Back
        // still stops the bounded experiment; no global overlay is introduced.
        if((!zfold && !compatFrames && !tent) || isFinishing || userLeaving) stopDemo()
        super.onStop()
    }
    private fun stopDemo() {
        generation++; probe?.close(); probe=null
        if(dual) { dualEnded=true; dualMessage="Проверка остановлена при выходе" }
        dualReady=false; clearSecondary(); Choreographer.getInstance().removeFrameCallback(frame)
        if(compatFrames) panelFrames.stop()
    }
    override fun onDestroy() { if(rawWindow) getSystemService(android.hardware.display.DisplayManager::class.java).unregisterDisplayListener(rawDisplayListener); stopDemo(); if(::stage.isInitialized) stage.removeCallbacks(rebuildTask); scene?.release(); super.onDestroy() }
    private fun commitRequestedFrame() {
        val phase=frameRequest ?: return
        val view=if(phase=="opening_cover") secondaryScene else scene
        if(view==null || !view.isAttachedToWindow || view.width<2 || view.height<2) return
        if(phase=="handoff") view.tilt=0f
        frameRequest=null
        val current=probe
        view.viewTreeObserver.registerFrameCommitCallback {
            if(current!=null && current===probe) current.frameCommitted()
        }
        view.invalidate()
    }
    private val pendingRetirements=mutableSetOf<Runnable>()
    private var secondaryExpiry: Runnable?=null
    private var primaryConfigurationChanged=false
    private fun uiEvent(text: String) {
        if(!frameContinuity) return
        android.util.Log.i("HingeWindow",text)
        zfoldJournal.addLast("${SystemClock.elapsedRealtime()} · UI $text")
        while(zfoldJournal.size>100) zfoldJournal.removeFirst()
    }
    // Frame commit confirms submission to the renderer, not physical panel scanout.
    private fun afterFrame(view: View, cleanup: () -> Unit) {
        var done=false
        lateinit var retire: Runnable
        retire=Runnable {
            if(!done) { done=true; handler.removeCallbacks(retire); pendingRetirements.remove(retire); cleanup() }
        }
        pendingRetirements.add(retire)
        handler.postDelayed(retire,1200)
        view.post {
            if(!done && view.isAttachedToWindow && view.isHardwareAccelerated) {
                view.viewTreeObserver.registerFrameCommitCallback { handler.post { if(!done) { uiEvent("frame_committed"); retire.run() } } }
                view.invalidate()
            }
        }
    }
    private fun retainSecondaryBriefly() {
        if(secondary==null || secondaryExpiry!=null) return
        val old=secondary
        val expiry=Runnable { secondaryExpiry=null; if(secondary===old) { uiEvent("handoff_window_expired"); clearSecondary() } }
        secondaryExpiry=expiry
        handler.postDelayed(expiry,1200)
        uiEvent("handoff_window_retained max_ms=1200")
    }
    private fun clearSecondary() {
        secondaryExpiry?.let { handler.removeCallbacks(it) }; secondaryExpiry=null
        pendingRetirements.toList().forEach { it.run() }
 secondaryScene?.release(); secondaryScene=null; secondaryStatus=null; runCatching { secondary?.dismiss() }; secondary=null; secondaryId=-1 }
    private fun updateSecondary() {
        val dm=getSystemService(android.hardware.display.DisplayManager::class.java)
        val candidate=dm.displays.firstOrNull { it.displayId!=display?.displayId && it.displayId==1 && it.state==Display.STATE_ON }
        if(candidate==null) { if(frameContinuity) retainSecondaryBriefly() else clearSecondary(); return }
        val size="${candidate.mode.physicalWidth}x${candidate.mode.physicalHeight}:${candidate.rotation}"
        if(secondaryId==candidate.displayId && secondarySize==size && secondary?.isShowing==true) {
            secondaryExpiry?.let { handler.removeCallbacks(it) }; secondaryExpiry=null
            return
        }
        if(frameContinuity) { replaceSecondaryWithFrame(candidate,size); return }
        clearSecondary()
        secondarySize=size
        secondaryInner=minOf(candidate.mode.physicalWidth,candidate.mode.physicalHeight)/resources.displayMetrics.density>=600f
        val presentation=android.app.Presentation(this,candidate)
        val container=FrameLayout(presentation.context)
        presentation.setContentView(container)
        presentation.window?.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { presentation.show() } catch(e: Exception) { dualMessage="Второй экран: ${e.javaClass.simpleName}"; return }
        secondary=presentation; secondaryId=candidate.displayId
        container.post {
            if(secondary!==presentation || container.width<2 || container.height<2) return@post
            val isInner=secondaryInner
            val bitmap=if(compatFrames) panelFrames.image(isInner,container.width,container.height,::texture) else texture(container.width,container.height)
            val view=HingeSceneView(presentation.context,bitmap,foldLine={ w,h,c -> HingeProjection.foldFor(isInner,w,h,c) })
            secondaryScene=view; container.addView(view,FrameLayout.LayoutParams(-1,-1)); view.tilt=if(compatFrames) panelFrames.tilt(isInner) else 0f
            secondaryStatus=TextView(presentation.context).apply { setTextColor(Color.WHITE); textSize=18f; setPadding(24,24,24,24); setBackgroundColor(0xdd111820.toInt()) }
            container.addView(secondaryStatus,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
            if(constantCover) container.addView(Button(presentation.context).apply { text="Проверить касание: $secondaryTaps"; setOnClickListener { secondaryTaps++; text="Касаний: $secondaryTaps" } },FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
            container.addView(Button(presentation.context).apply { text="Завершить проверку двух экранов"; setOnClickListener { finish() } },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        }
    }
    private fun replaceSecondaryWithFrame(candidate: Display, size: String) {
        val isInner=minOf(candidate.mode.physicalWidth,candidate.mode.physicalHeight)/resources.displayMetrics.density>=600f
        val presentation: android.app.Dialog=if(rawWindow) RetainedDisplayDialog(this,candidate,::uiEvent) else android.app.Presentation(this,candidate)
        val container=FrameLayout(presentation.context)
        val dimensions=Point().also { candidate.getRealSize(it) }
        if(dimensions.x<2 || dimensions.y<2) return
        val bitmap=texture(dimensions.x,dimensions.y)
        // Opaque content exists before WindowManager can expose this window.
        container.background=android.graphics.drawable.BitmapDrawable(presentation.context.resources,bitmap)
        val view=HingeSceneView(presentation.context,bitmap,foldLine={ w,h,c -> HingeProjection.foldFor(isInner,w,h,c) })
        view.config=scene?.config ?: EffectProfile()
        view.tilt=if(received>0 && SystemClock.elapsedRealtime()-received<500) projection(displayed,view.config,isInner) else 0f
        container.addView(view,FrameLayout.LayoutParams(-1,-1))
        val label=TextView(presentation.context).apply { setTextColor(Color.WHITE); textSize=18f; setPadding(24,24,24,24); setBackgroundColor(0xdd111820.toInt()) }
        container.addView(label,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        container.addView(Button(presentation.context).apply { text="Завершить проверку двух экранов"; setOnClickListener { finish() } },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        presentation.setContentView(container)
        presentation.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setBackgroundDrawable(android.graphics.drawable.BitmapDrawable(presentation.context.resources,bitmap))
            setWindowAnimations(0)
        }
        try { presentation.show() } catch(e: Exception) { view.release(); runCatching { presentation.dismiss() }; dualMessage="Второй экран: ${e.javaClass.simpleName}"; return }
        val old=secondary; val oldScene=secondaryScene
        secondaryExpiry?.let { handler.removeCallbacks(it) }; secondaryExpiry=null
        secondary=presentation; secondaryScene=view; secondaryStatus=label
        secondaryId=candidate.displayId; secondarySize=size; secondaryInner=isInner
        uiEvent("secondary_prepared display=${candidate.displayId} size=$size")
        afterFrame(view) { oldScene?.release(); runCatching { old?.dismiss() }; uiEvent("previous_window_retired") }
    }
    private fun texture(w: Int,h: Int): Bitmap {
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val c=Canvas(bitmap); val p=Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader=LinearGradient(0f,0f,w.toFloat(),h.toFloat(),intArrayOf(0xff243a61.toInt(),0xff182133.toInt(),0xff665685.toInt()),null,Shader.TileMode.CLAMP)
        c.drawRect(0f,0f,w.toFloat(),h.toFloat(),p); p.shader=null
        val unit=minOf(w,h)/100f
        p.color=0x227ddacb; c.drawCircle(w*.8f,h*.3f,w*.4f,p)
        p.color=Color.WHITE; p.textSize=unit*6; c.drawText("Hinge Lab",w*.06f,h*.1f,p)
        p.textSize=unit*2; c.drawText("Экран для проверки складывания",w*.06f,h*.14f,p)
        p.color=0x604e799c; c.drawRoundRect(w*.06f,h*.2f,w*.46f,h*.43f,unit*3,unit*3,p)
        p.color=0x60486d6c; c.drawRoundRect(w*.54f,h*.2f,w*.94f,h*.43f,unit*3,unit*3,p)
        p.color=Color.WHITE; p.textSize=unit*6; c.drawText("09:41",w*.09f,h*.32f,p)
        p.textSize=unit*4; c.drawText("24°",w*.58f,h*.32f,p)
        val colors=intArrayOf(0xff77bcb1.toInt(),0xff939fe0.toInt(),0xffd6ac79.toInt(),0xffcb8998.toInt())
        for(row in 0..1) for(col in 0..3) {
            val x=w*(.12f+col*.24f); val y=h*(.51f+row*.15f)
            p.color=colors[(row+col)%4]; c.drawRoundRect(x-unit*4,y,x+unit*4,y+unit*8,unit*2,unit*2,p)
            p.color=Color.WHITE; p.textSize=unit*3; c.drawText(listOf("♪","✦","+","◉")[col],x-unit,y+unit*5.4f,p)
        }
        return bitmap
    }
}
