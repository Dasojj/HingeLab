package dev.duohome.hingelab

import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.stream.AdbStream
import java.io.Closeable

/** Angle-only adaptation of ZFoldDuo's wallpaper probe (MIT; see assets). */
class AngleProbe(private val sample: (Float) -> Unit, private val status: (String) -> Unit,
    private val diagnostic: (String) -> Unit, private val finished: () -> Unit) : Closeable {
    private val lock = Any()
    @Volatile private var stopped = false
    private var adb: Kadb? = null
    private var control: Kadb? = null
    private var log: AdbStream? = null
    private var poll: AdbStream? = null
    private var helperPid: Int? = null

    fun run(port: Int) {
        try {
            status("Подключаемся к ADB этого телефона…")
            val a = Kadb.create("127.0.0.1", port, connectTimeout = 5000)
            synchronized(lock) { if (stopped) { a.close(); return }; adb = a }
            val c = Kadb.create("127.0.0.1", port, connectTimeout = 5000)
            synchronized(lock) { if (stopped) { c.close(); return }; control = c }
            diagnostic("ADB shell: " + c.shell("id").allOutput.trim().take(300))
            diagnostic("Компонент обоев: " + c.shell("dumpsys wallpaper | grep -E 'mWallpaperComponent=|mNextWallpaperComponent=' | head -n 8").allOutput.trim().take(600).ifEmpty { "не указан в dumpsys" })
            // Subscribe before probing so the first response is not lost.
            // Firmware may rename the log tag; filter for our action instead of
            // relying on a single wallpaper implementation's exact tag.
            val stream = a.open("shell:logcat -v brief -T 1 --regex='(hingelab_angle|FoldInteractive.*(mCurrentAngle|unregisterSensor))' '*:V'")
            synchronized(lock) { if (stopped) { stream.close(); return }; log = stream }
            status("ADB подключён. Проверяем интерфейс Samsung…")
            val polling = a.open("shell:CLASSPATH=\"\$(pm path dev.duohome.hingelab | head -n 1 | cut -d: -f2)\" exec app_process /system/bin dev.duohome.hingelab.WallpaperBridge")
            synchronized(lock) { if (stopped) { polling.close(); return }; poll = polling }
            var ready = false
            var headerLines = 0
            while (!stopped && !ready) {
                val line = polling.source.readUtf8Line() ?: error("Проверка интерфейса Samsung завершилась без подтверждения")
                if (++headerLines > 100) error("Нет подтверждения готовности интерфейса Samsung")
                diagnostic(line.take(600))
                if(line.startsWith("HL_PID ")) synchronized(lock) { helperPid=line.removePrefix("HL_PID ").trim().toIntOrNull()?.takeIf { it>1 } }
                if (line.startsWith("HL_ERROR")) error(line.removePrefix("HL_ERROR "))
                ready = line.startsWith("HL_READY")
            }
            if (stopped) return
            Thread({
                try {
                    while (!stopped) {
                        val line = polling.source.readUtf8Line() ?: break
                        if (line.startsWith("HL_ERROR")) { diagnostic(line); status(line); return@Thread }
                        if (line.startsWith("HL_DONE")) diagnostic(line)
                    }
                    if (!stopped) status("Пробный сеанс завершён. Нажми «Начать» для повторения.")
                } catch (e: Exception) { if (!stopped) status("Поток запросов завершён: ${e.message}") }
                finally { close() }
            }, "hinge-lab-poll-watch").start()
            status("ADB подключён. Ждём угол Samsung…")
            var lines = 0
            while (!stopped) {
                val line = stream.source.readUtf8Line() ?: error("Поток ADB завершён")
                if (!line.contains("adbd") && (line.contains("hingelab_angle") || line.contains("FoldInteractive"))) {
                    if (lines++ < 6) diagnostic("Строка Samsung: " + line.take(450))
                }
                AngleData.parse(line)?.let(sample)
            }
        } catch (e: Exception) {
            if (!stopped) status("Измерение не запущено: ${e.message ?: e.javaClass.simpleName}")
        } finally { close(); finished() }
    }

    override fun close() {
        synchronized(lock) {
            stopped = true
            helperPid?.let { pid -> runCatching { control?.shell("kill $pid") } }
            helperPid = null
            runCatching { poll?.close() }; poll = null
            runCatching { log?.close() }; log = null
            runCatching { control?.close() }; control = null
            runCatching { adb?.close() }; adb = null
        }
    }
}
