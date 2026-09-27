package dev.duohome.hingelab

import android.app.Activity
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket

class ShellAngleProbe(private val activity: Activity, private val sample: (Float) -> Unit,
    private val status: (String) -> Unit, private val diagnostic: (String) -> Unit,
    private val finished: () -> Unit, private val health: (String) -> Unit = {},
    private val dualDisplay: Boolean = false, private val experimentMode: Int = 69) : Closeable {
    @Volatile private var stopped = false
    private val socket = Socket()
    private val replies=java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task,"hingelab-frame-reply").apply { isDaemon=true }
    }
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private var sentPanel=0
    private val panelWatch=object: Runnable {
        override fun run() {
            if(stopped) return
            val bounds=activity.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
            val panel=if(minOf(bounds.width(),bounds.height())/activity.resources.displayMetrics.density>=600f) 5 else 17
            if(panel!=sentPanel) {
                sentPanel=panel
                runCatching { socket.getOutputStream().apply { write(panel); flush() } }
                diagnostic("Активное окно: адресат $panel")
            }
            handler.postDelayed(this,250)
        }
    }
    fun run() {
        try {
            socket.connect(InetSocketAddress("127.0.0.1", 43987), 3000)
            socket.soTimeout = 5000
            socket.getOutputStream().apply { write(activity.filesDir.resolve("shell-token").readBytes()); flush() }
            socket.getInputStream().bufferedReader().use { reader ->
                val hello = reader.readLine()
                check(hello == "HELLO shell uid=2000") { "Помощник не подтвердил доступ" }
                val bounds=activity.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
                val inner=minOf(bounds.width(),bounds.height())/activity.resources.displayMetrics.density>=600f
                socket.getOutputStream().apply { write(if(dualDisplay) experimentMode else if(inner) 5 else 17); flush() }
                sentPanel=if(inner) 5 else 17
                if(!dualDisplay) handler.post(panelWatch)
                diagnostic("Адресат обоев: ${if(inner) "внутренний (5)" else "внешний (17)"}")
                health("connected")
                diagnostic("$hello; приложение uid=${android.os.Process.myUid()}; ADB и READ_LOGS в приложении не используются")
                status("Shell-помощник подключён. Ждём угол…")

                while (!stopped) {
                    val line = reader.readLine() ?: error("Помощник отключился")
                    when {
                        line.startsWith("POSTURE ") -> health(line)
                        line.startsWith("ZFD ") -> { diagnostic(line); health(line) }
                        line.startsWith("DISPLAY ") -> { diagnostic(line); health(line) }
                        line.startsWith("DIRECT_ANGLE ") -> DirectAngleData.parse(line)?.let { health("direct=events"); sample(it) }
                        line.startsWith("DIRECT ") -> { health("direct="+line.removePrefix("DIRECT ")); diagnostic(line) }
                        line == "SOURCE shell" -> { health("source=shell"); diagnostic("Запросы отправляет shell-помощник независимо от окна Lab") }
                        line.startsWith("CACHE ") -> health("visible=false")
                        line == "HEARTBEAT" -> health("heartbeat")
                        line.startsWith("ERROR ") -> { status("Помощник: $line"); health("error") }
                        line.startsWith("SESSION ") -> {
                            val name=line.removePrefix("SESSION ")
                            if(name.matches(Regex("hingelab_angle_[a-f0-9]+"))) { health("session") }
                        }
                        line.startsWith("META ") -> health(line)
                        line.startsWith("ANGLE ") -> {
                            line.removePrefix("ANGLE ").substringBefore(' ').toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..180f }?.let(sample)
                            if(line.contains("visible=")) health("visible="+line.substringAfter("visible="))
                        }
                    }
                }
            }
        } catch (e: Exception) { if (!stopped) status("Shell-помощник: ${e.message}. Отсутствие ответов не доказывает остановку датчика. После обновления APK заново запусти помощник кнопкой «Запустить помощник · автоматически».") }
        finally { close(); finished() }
    }
    fun frameCommitted() {
        if(stopped || experimentMode!=70) return
        // Frame callbacks can run on the main thread. A socket write there is
        // rejected by Android; the old runCatching silently lost the ACK.
        try {
            replies.execute {
                if(!stopped) try {
                    socket.getOutputStream().apply { write(80); flush() }
                    health("ZFD frame_ack sent")
                } catch(e: Exception) {
                    if(!stopped) health("ZFD error frame_ack ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        } catch(_: java.util.concurrent.RejectedExecutionException) {
            // The session was closed between the check and enqueue.
        }
    }
    override fun close() { stopped = true; handler.removeCallbacks(panelWatch); runCatching { socket.close() }; replies.shutdownNow() }
}
