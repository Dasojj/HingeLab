package dev.duohome.hingelab

import android.app.Activity
import android.os.*
import android.content.*
import android.content.res.Configuration
import android.widget.*

/** No keep-screen-on flag, display request, or automatic launch. */
class PostureActivity: Activity() {
    private val handler=Handler(Looper.getMainLooper())
    private val rows=ArrayDeque<String>()
    private lateinit var text: TextView
    private var probe: ShellAngleProbe?=null
    private var leaving=false
    private var started=0L
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        root.addView(TextView(this).apply { textSize=20f; text="Раннее открытие · только наблюдение" })
        root.addView(TextView(this).apply { text="Начни на полностью сложенном телефоне. Медленно приоткрой его, задержись на маленьком угле, затем раскрой. Запись длится 90 секунд. Экраны переключает только Samsung. BASE и lid — состояния, не точные градусы. Первая строка может быть начальным состоянием." })
        root.addView(Button(this).apply { text="Начать запись"; setOnClickListener { start() } })
        root.addView(Button(this).apply { text="Остановить"; setOnClickListener { stop() } })
        root.addView(Button(this).apply { text="Скопировать результат"; setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Раннее открытие",rows.joinToString("\n")))
            Toast.makeText(this@PostureActivity,"Скопировано",Toast.LENGTH_SHORT).show()
        } })
        text=TextView(this).apply { textSize=14f }
        root.addView(ScrollView(this).apply { addView(text) },LinearLayout.LayoutParams(-1,0,1f))
        root.addView(Button(this).apply { text="Назад"; setOnClickListener { finish() } })
        setContentView(root)
    }
    private fun add(line: String) {
        rows.addLast("${SystemClock.elapsedRealtime()-started} мс · $line")
        while(rows.size>400) rows.removeFirst()
        text.text=rows.joinToString("\n")
    }
    private fun start() {
        stop(); rows.clear(); started=SystemClock.elapsedRealtime(); leaving=false
        lateinit var current: ShellAngleProbe
        current=ShellAngleProbe(this,{}, { s -> handler.post { if(probe===current) add(s) } },{},
            { handler.post { if(probe===current) { add("Запись завершена"); probe=null } } },
            { e -> handler.post { if(probe===current && e.startsWith("POSTURE ")) add(e) } },true,75)
        probe=current
        add("Hinge Lab · пассивная проверка; управление экранами отключено")
        add("WINDOW "+if(resources.configuration.smallestScreenWidthDp>=600) "внутренний/широкий" else "внешний/узкий")
        Thread({ current.run() },"posture-client").start()
    }
    private fun stop() { probe?.close(); probe=null }
    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        if(probe!=null) add("WINDOW "+if(c.smallestScreenWidthDp>=600) "внутренний/широкий" else "внешний/узкий")
    }
    override fun onUserLeaveHint() { leaving=true; super.onUserLeaveHint() }
    override fun onStop() { if(isFinishing || leaving) stop(); super.onStop() }
    override fun onDestroy() { stop(); super.onDestroy() }
}
