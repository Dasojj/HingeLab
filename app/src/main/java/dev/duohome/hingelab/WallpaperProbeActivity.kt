package dev.duohome.hingelab

import android.app.Activity
import android.app.WallpaperManager
import android.content.*
import android.os.*
import android.util.Log
import android.widget.*

/** Diagnostic entry points only: never applies wallpaper or attaches an engine. */
class WallpaperProbeActivity : Activity() {
    private val target = ComponentName("com.samsung.android.wallpaper.live", "com.samsung.android.wallpaper.live.fold.FoldInteractive")
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var output: TextView
    private var bound = false
    private var probe: ShellAngleProbe? = null
    private var previewLaunched = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            record("BIND connected: $name; descriptor=${runCatching { binder.interfaceDescriptor }.getOrNull()}")
            release()
        }
        override fun onServiceDisconnected(name: ComponentName) { record("BIND disconnected: $name") }
        override fun onNullBinding(name: ComponentName) { record("BIND null: $name"); release() }
        override fun onBindingDied(name: ComponentName) { record("BIND died: $name"); release() }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 32) }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(TextView(this).apply {
            text = "Предпросмотр Samsung · проверка доступа\nОбои не применяются. Из системного предпросмотра возвращайся кнопкой «Назад», не нажимая «Готово» или «Установить»."
            textSize = 18f
        })
        fun button(title: String, action: () -> Unit) {
            root.addView(Button(this).apply { text = title; isAllCaps = false; setOnClickListener { action() } })
        }
        button("Новый тест: предпросмотр + режим 5 · 60 секунд") {
            android.app.AlertDialog.Builder(this).setTitle("Проверка без смены режима экранов")
                .setMessage("Сначала обнови помощник кнопкой «Запустить помощник · автоматически» в Lab. Затем полностью сложи телефон и начни тест с внешнего экрана. Оба экрана будут включены только на минуту. Предпросмотр откроется автоматически после готовности режима №5. Медленно раскрой и сложи телефон дважды. Если фон чёрный, всё равно заверши один цикл. Кнопку «Готово» не нажимай. После минуты вернись через «Назад» и скопируй журнал. Запись показывает состояние подписки, а не непрерывные измерения угла. При входе и выходе из теста ещё возможен системный переход.")
                .setNegativeButton("Отмена", null).setPositiveButton("Начать") { _, _ -> startPreviewTest() }.show()
        }
        button("Остановить тест") { stopPreviewTest() }
        button("1. Проверить подключение к службе") { bindProbe() }
        button("2. Стандартный предпросмотр") {
            launch(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, target), 101)
        }
        button("3. Предпросмотр Samsung") {
            openSamsungPreview()
        }
        button("Скопировать журнал") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Wallpaper probe", output.text))
        }
        button("Назад в лабораторию") { finish() }
        output = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        root.addView(output)
        output.text = getSharedPreferences("wallpaper_probe", MODE_PRIVATE).getString("log", "")
        record("START uid=${Process.myUid()} display=${display?.displayId}")
    }

    private fun openSamsungPreview() {
        // Matches Samsung's interactive preview intent builder (o4.o.h).
        launch(Intent("com.samsung.intent.action.SHOW_PRELOADED_LIVE_WALLPAPER_PREVIEW")
            .setPackage("com.samsung.android.app.dressroom")
            .putExtra("type", "interactive").putExtra("subType", 10)
            .putExtra("locType", "preload")
            .putExtra("packageName", "com.samsung.android.wallpaper.res")
            .putExtra("live_class_name", target.className)
            // Resource name/frame read from this test phone's current wallpaper.
            .putExtra("fileName", "video_001.mp4").putExtra("frameNo", 545)
            .putExtra("which", 5), 102)
    }

    private fun startPreviewTest() {
        if (probe != null) { record("Тест уже идёт"); return }
        previewLaunched = false
        output.text = "Hinge Lab 0.31 · предпросмотр в постоянном режиме 5\n"
        lateinit var current: ShellAngleProbe
        current = ShellAngleProbe(this, {},
            { message -> handler.post { if (probe === current) record(message) } }, {},
            { handler.post { if (probe === current) { probe = null; record("Соединение завершено. Если нет preview_ready, обнови помощник через «Запустить помощник · автоматически».") } } },
            { event -> handler.post {
                if (probe === current && event.startsWith("DISPLAY ")) {
                    record(event)
                    if (event == "DISPLAY preview_ready" && !previewLaunched) {
                        previewLaunched = true
                        openSamsungPreview()
                    }
                }
            } }, true, 80)
        probe = current
        record("START bounded test; close phone required; no angle stream claimed")
        Thread({ current.run() }, "preview-test-client").start()
    }

    private fun stopPreviewTest() {
        probe?.close(); probe = null
        record("Остановка запрошена; shell-помощник освобождает экраны")
    }

    private fun bindProbe() {
        release()
        try {
            bound = bindService(Intent().setComponent(target), connection, BIND_AUTO_CREATE)
            record("BIND accepted=$bound")
            if (bound) handler.postDelayed({ if (bound) { record("BIND timeout; released"); release() } }, 3000)
        } catch (e: Exception) { record("BIND ${e.javaClass.simpleName}: ${e.message}") }
    }

    private fun launch(intent: Intent, code: Int) {
        record("LAUNCH $code ${intent.action}")
        try { startActivityForResult(intent, code) }
        catch (e: Exception) { record("LAUNCH $code ${e.javaClass.simpleName}: ${e.message}") }
    }

    @Deprecated("Diagnostic legacy result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        record("RESULT $requestCode code=$resultCode")
    }

    private fun record(message: String) {
        val line = "${SystemClock.elapsedRealtime()} · $message"
        Log.i("HLWallpaperProbe", line)
        output.text = (output.text.toString() + "\n" + line).takeLast(16000)
        getSharedPreferences("wallpaper_probe", MODE_PRIVATE).edit().putString("log", output.text.toString()).apply()
    }

    private fun release() {
        if (bound) { runCatching { unbindService(connection) }; bound = false }
    }
    override fun onDestroy() { probe?.close(); probe = null; release(); handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
