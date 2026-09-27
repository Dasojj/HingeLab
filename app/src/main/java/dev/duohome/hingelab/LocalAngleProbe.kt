package dev.duohome.hingelab

import android.app.Activity
import android.app.WallpaperManager
import android.os.Handler
import android.os.Looper
import java.io.Closeable

/** Public wallpaper command + log access granted once from USB. No ADB client. */
class LocalAngleProbe(private val activity: Activity, private val sample: (Float) -> Unit,
    private val status: (String) -> Unit, private val diagnostic: (String) -> Unit,
    private val finished: () -> Unit) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    private val lock = Any()
    private var process: Process? = null
    private val command = object : Runnable {
        override fun run() {
            if (stopped) return
            try {
                val token = activity.window.decorView.windowToken ?: error("Окно ещё не готово")
                WallpaperManager.getInstance(activity).sendWallpaperCommand(token, "hingelab_angle", 0, 0, 0, null)
                handler.postDelayed(this, 50)
            } catch (e: Exception) {
                status("Команда обоям отклонена: ${e.message}")
                close()
            }
        }
    }
    fun run() {
        try {
            diagnostic("Локальный режим: uid=${android.os.Process.myUid()}, без ADB-соединения")
            if (activity.checkSelfPermission("android.permission.READ_LOGS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                error("Нужна однократная выдача READ_LOGS через USB")
            val p = ProcessBuilder("logcat", "-v", "brief", "-T", "1", "--regex=hingelab_angle", "*:V").redirectErrorStream(true).start()
            synchronized(lock) { if (stopped) { p.destroy(); return }; process = p }
            status("Локальное чтение запущено. Разреши доступ к журналу, если Android спросит.")
            handler.postDelayed(command, 300)
            var count = 0
            p.inputStream.bufferedReader().use { reader ->
                while (!stopped) {
                    val line = reader.readLine() ?: break
                    val angle = AngleData.parse(line)
                    if (angle != null) {
                        if (count++ < 3) diagnostic("Локальный ответ Samsung: ${line.take(400)}")
                        sample(angle)
                    }
                }
            }
            if (!stopped) status("Локальный поток журнала завершён")
        } catch (e: Exception) { if (!stopped) status("Локальный режим: ${e.message}") }
        finally { close(); finished() }
    }
    override fun close() {
        stopped = true
        handler.removeCallbacks(command)
        synchronized(lock) { process?.destroy(); process = null }
    }
}
