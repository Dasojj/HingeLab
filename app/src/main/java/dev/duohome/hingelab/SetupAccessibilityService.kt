package dev.duohome.hingelab

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.*

/** Explicit, bounded setup flow. Never collects arbitrary window text or clicks by coordinates. */
class SetupAccessibilityService : AccessibilityService() {
    companion object { var instance: SetupAccessibilityService? = null; private set }
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var hintView: android.widget.TextView?=null
    private fun hideHint() {
        hintView?.let { runCatching { getSystemService(android.view.WindowManager::class.java).removeView(it) } }
        hintView=null
    }
    private fun showHint(message: String) {
        if(hintView==null) {
            val density=resources.displayMetrics.density
            val view=android.widget.TextView(this).apply {
                textSize=16f; setTextColor(android.graphics.Color.WHITE)
                setPadding((18*density).toInt(),(12*density).toInt(),(18*density).toInt(),(12*density).toInt())
                background=android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xf0223344.toInt()); cornerRadius=16*density
                }
            }
            val params=android.view.WindowManager.LayoutParams(
                minOf((360*density).toInt(),resources.displayMetrics.widthPixels-(32*density).toInt()),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT).apply {
                    gravity=android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL; y=(8*density).toInt()
                }
            runCatching { getSystemService(android.view.WindowManager::class.java).addView(view,params); hintView=view }
        }
        hintView?.text=message
    }
    private var active = false
    private var busy = false
    private var pairingNeeded = true
    private var paired = false
    private var started = 0L
    private var scrolls = 0
    private var unknown = 0
    private var session = 0
    private var settleUntil = 0L
    private var reverse = false
    private var rowAttempts = 0
    private var searching=false
    private var queryEntered=false
    private var waitingForWirelessPage=false
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            if (SystemClock.elapsedRealtime() - started > 120000) { finish("Время настройки истекло. Продолжи вручную."); return }
            if (!busy && SystemClock.elapsedRealtime() >= settleUntil) step()
            if (active) handler.postDelayed(this, 650)
        }
    }
    override fun onServiceConnected() { instance=this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { cancel() }
    override fun onDestroy() { cancel(); scope.cancel(); instance=null; super.onDestroy() }
    fun cancel() { hideHint(); active=false; session++; handler.removeCallbacks(tick) }
    fun begin() {
        cancel(); active=true; busy=false; pairingNeeded=true; paired=false; scrolls=0; unknown=0
        started=SystemClock.elapsedRealtime()
        settleUntil=0; reverse=false; rowAttempts=0
        searching=false; queryEntered=false
        waitingForWirelessPage=false
        record("Открываем параметры разработчика")
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { finish("Открой параметры разработчика вручную."); return }
        handler.postDelayed(tick, 1000)
    }
    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result=ArrayList<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (result.size>=1500 || depth>40) return
            result.add(node)
            for(i in 0 until node.childCount) node.getChild(i)?.let { visit(it,depth+1) }
        }
        visit(root,0); return result
    }
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo?=node
        repeat(10) {
            val n=current ?: return false
            if(n.isClickable && n.isEnabled) {
                val accepted=n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                settleUntil=SystemClock.elapsedRealtime()+1200
                return accepted
            }
            current=n.parent
        }
        return false
    }
    private fun step() {
        // Scrolls recycle preference rows. Without scroll events the accessibility
        // cache can keep the previous page even while Samsung visibly moves on.
        if(android.os.Build.VERSION.SDK_INT>=33) clearCache()
        val roots=(listOfNotNull(rootInActiveWindow) + windows.sortedByDescending { it.isFocused }.mapNotNull { it.root })
            .filter { it.packageName?.toString() in setOf("com.android.settings","com.android.settings.intelligence") }
        if(roots.isEmpty()) {
            hideHint()
            if (++unknown>6) finish("Настройка остановлена: открыт другой экран.", returnToApp=false)
            return
        }
        val all=roots.flatMap { nodes(it) }
        fun id(name: String)=all.firstOrNull { it.viewIdResourceName=="com.android.settings:id/$name" }
        fun text(node: AccessibilityNodeInfo)=node.text?.toString().orEmpty()
        if(searching && !waitingForWirelessPage) {
            val search=all.firstOrNull { it.viewIdResourceName?.endsWith(":id/search_src_text")==true }
            val inSearch=search?.packageName?.toString()=="com.android.settings.intelligence"
            if(search!=null && !inSearch) { click(search); return }
            if(search!=null && inSearch && !queryEntered) {
                val query=if(resources.configuration.locales[0].language=="ru") "Отладка по Wi-Fi" else "Wireless debugging"
                val args=android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,query) }
                if(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)) {
                    queryEntered=true
                    search.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    settleUntil=SystemClock.elapsedRealtime()+2200
                }
                return
            }
        }
        val code=id("pairing_code")
        if(code!=null) {
            waitingForWirelessPage=false; hideHint()
            if(paired) { performGlobalAction(GLOBAL_ACTION_BACK); return }
            val secret=text(code).filter { it.isDigit() }
            val port=id("ip_addr")?.let { endpointPort(text(it)) }
            if(secret.length!=6 || port==null) { finish("Не удалось прочитать код сопряжения. Введи его вручную."); return }
            busy=true; val token=session
            record("Код доступен. Выполняем сопряжение с этим телефоном")
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { Kadb.pair("127.0.0.1",port,secret,"Hinge Lab") }
                    if(token==session && active) { paired=true; record("Сопряжение выполнено") }
                } catch (_: Exception) { if(token==session) finish("Сопряжение не выполнено. Попробуй вручную.") }
                finally { if(token==session) busy=false }
            }
            return
        }
        // A known consent dialog is left for the user, never guessed from a generic OK button.
        if(all.any { it.viewIdResourceName=="android:id/button1" }) {
            record("Подтверди системный запрос вручную; затем продолжим")
            return
        }
        val pairRow=all.firstOrNull { SetupLabels.pairing(text(it)) }
        if(pairRow!=null) {
            waitingForWirelessPage=false; hideHint()
            unknown=0
            if(pairingNeeded && !paired) { record("Открываем код сопряжения"); click(pairRow); return }
            val port=all.firstNotNullOfOrNull { endpointPort(text(it)) }
            if(port==null) return
            busy=true; val token=session
            record("Запускаем помощник и выключаем беспроводную отладку")
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { HelperBootstrap.start(this@SetupAccessibilityService,port,true) }
                    delay(3000)
                    if(token==session && active) finish("Автоматическая настройка завершена", success=true)
                } catch (e: Exception) {
                    if(e is CancellationException) throw e
                    if(token==session) finish("Помощник не запущен: ${e.message?.take(300) ?: e.javaClass.simpleName}")
                }
                finally { if(token==session) busy=false }
            }
            return
        }
        val toggle=id("sesl_switchbar_switch")
        val wirelessPage=all.any { it.viewIdResourceName=="com.android.settings:id/sesl_switchbar_container" && SetupLabels.wireless(text(it)) } ||
            all.any { SetupLabels.normalize(text(it)).contains("включите отладку по wi fi") }
        if(wirelessPage && toggle!=null) {
            waitingForWirelessPage=false; hideHint()
            if(!toggle.isChecked) { record("Включаем беспроводную отладку"); click(toggle) }
            else if(++unknown>15) finish("Нет доступного подключения. Проверь Wi-Fi и системные подсказки.")
            return
        }
        if(waitingForWirelessPage) { showHint("Нажмите подсвеченный пункт\n«Отладка по Wi-Fi».\nДальше Hinge Lab продолжит сам."); return }
        val row=all.firstOrNull { SetupLabels.wireless(text(it)) && it.viewIdResourceName=="android:id/title" }
        if(row!=null) {
            unknown=0
            if (++rowAttempts>3) { finish("Пункт найден, но не открывается. Нажми «Отладка по Wi-Fi» вручную и повтори настройку."); return }
            record("Открываем беспроводную отладку")
            val accepted=click(row)
            if(accepted && row.packageName?.toString()=="com.android.settings.intelligence") {
                waitingForWirelessPage=true
                val hint="Если открылись параметры разработчика: нажми подсвеченную строку «Отладка по Wi-Fi». Затем продолжу автоматически."
                record(hint)
                showHint("Нажмите подсвеченный пункт\n«Отладка по Wi-Fi».\nДальше Hinge Lab продолжит сам.")
            }
            if (!accepted) {
                row.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                settleUntil=SystemClock.elapsedRealtime()+1000
            }
            return
        }
        if(Settings.Global.getInt(contentResolver,"development_settings_enabled",0)==0) {
            finish("Сначала включи параметры разработчика вручную: сведения о ПО → номер сборки. Затем повтори настройку."); return
        }
        // Search results can expose a navigation entry even when a switch row
        // is omitted from the developer page's accessibility hierarchy.
        if(!searching && scrolls>=2) {
            searching=true; record("Ищем пункт через поиск настроек")
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            settleUntil=SystemClock.elapsedRealtime()+1400; return
        }
        if(searching && all.any { it.packageName?.toString()=="com.android.settings.intelligence" }) {
            val results=all.firstOrNull { it.isScrollable && it.packageName?.toString()=="com.android.settings.intelligence" }
            if(++unknown<=6 && results?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)==true) { settleUntil=SystemClock.elapsedRealtime()+1200; return }
            if(unknown>8) finish("Поиск не открыл отладку. Открой «Отладка по Wi-Fi» вручную и повтори настройку.")
            return
        }
        // Prefer the right-hand developer pane, never the left Settings navigation.
        val scroller=all.filter { it.isScrollable }.maxByOrNull {
            val bounds=android.graphics.Rect(); it.getBoundsInScreen(bounds); bounds.centerX()
        }
        if(scrolls++<24 && scroller!=null) {
            val moved=scroller.performAction(if(reverse) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            settleUntil=SystemClock.elapsedRealtime()+1400
            if(moved) return
            if(!reverse) { reverse=true; return }
        }
        finish("Не найден знакомый пункт беспроводной отладки. Продолжи вручную.")
    }
    private fun endpointPort(value: String): Int? {
        if(!value.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}:\\d{2,5}"))) return null
        return value.substringAfterLast(':').toIntOrNull()?.takeIf { it in 1..65535 }
    }
    private fun record(message: String) {
        if(active && !waitingForWirelessPage) showHint(message)
        android.util.Log.i("HingeLabSetup",message)
        getSharedPreferences("setup",MODE_PRIVATE).edit().putString("status",message).apply()
    }
    private fun finish(message: String, success: Boolean=false, returnToApp: Boolean=true) {
        cancel(); record(message)
        getSharedPreferences("setup",MODE_PRIVATE).edit().putBoolean("start_measurement",success).apply()
        if(returnToApp) startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}
