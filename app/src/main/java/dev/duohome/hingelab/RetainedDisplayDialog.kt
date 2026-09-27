package dev.duohome.hingelab

import android.app.Dialog
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager

// Framework TYPE_PRESENTATION is hidden from SDK stubs; same value used by stock Presentation.
private const val PRESENTATION_WINDOW_TYPE=2037

/** Same window type/context as Presentation, without Samsung's FLAG_REAR cancellation. */
class RetainedDisplayDialog(context: Context, private val target: Display, private val event: (String) -> Unit) :
    Dialog(ContextThemeWrapper(context.createDisplayContext(target)
        .createWindowContext(PRESENTATION_WINDOW_TYPE, null), R.style.AppTheme)) {
    private val manager=context.getSystemService(DisplayManager::class.java)
    private val key=Integer.toHexString(System.identityHashCode(this))
    private var registered=false
    private var lastState=""
    private val listener=object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) { if(id==target.displayId) record("added") }
        override fun onDisplayChanged(id: Int) { if(id==target.displayId) record("changed") }
        override fun onDisplayRemoved(id: Int) {
            if(id==target.displayId) { event("raw_window $key display_removed; dismiss"); dismiss() }
        }
    }
    init {
        window?.apply {
            setType(PRESENTATION_WINDOW_TYPE)
            setGravity(Gravity.FILL)
            setLayout(-1,-1)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(0)
        }
        setCanceledOnTouchOutside(false)
    }
    private fun record(reason: String) {
        val state="id=${target.displayId} valid=${target.isValid} state=${target.state} rear=${target.flags and 8192 != 0} showing=$isShowing"
        if(state!=lastState || reason=="start") { lastState=state; event("raw_window $key $reason $state") }
    }
    override fun onStart() {
        super.onStart()
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper())); registered=true
        window?.setLayout(-1,-1)
        record("start")
    }
    override fun onStop() {
        if(registered) { manager.unregisterDisplayListener(listener); registered=false }
        event("raw_window $key stopped")
        super.onStop()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); event("raw_window $key attached") }
    override fun onDetachedFromWindow() { event("raw_window $key detached"); super.onDetachedFromWindow() }
    override fun cancel() { event("raw_window $key cancel"); super.cancel() }
    override fun dismiss() { event("raw_window $key dismiss_requested"); super.dismiss() }
}
