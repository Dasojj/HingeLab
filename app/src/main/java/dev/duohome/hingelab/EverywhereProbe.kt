package dev.duohome.hingelab

import android.content.Context
import android.os.SystemClock
import java.net.Socket
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fixed-mode authenticated connection. No shell commands supplied by the UI. */
class EverywhereProbe(private val context: Context, private val mode: Int,
    private val line: (String) -> Unit, private val ended: (String, Boolean) -> Unit) : AutoCloseable {
    private val socket=Socket()
    @Volatile private var closed=false
    @Volatile var uiAlive=SystemClock.elapsedRealtime()
    private val heartbeat=Executors.newSingleThreadScheduledExecutor()
    var earlyEntry=false
    fun restoreTransfer() { if(!closed) socket.getOutputStream().apply { write(202); flush() } }
    fun requestFocusCheck() {
        if(closed) return
        runCatching { heartbeat.execute { if(!closed) runCatching { socket.getOutputStream().apply { write(204); flush() } } } }
    }
    fun requestTransfer() { if(!closed) socket.getOutputStream().apply { write(201); flush() } }
    fun run() {
        var reason="Помощник завершил сеанс; включи режим снова"
        var retryable=true
        try {
            require(mode in setOf(71,86,87,88))
            socket.connect(InetSocketAddress("127.0.0.1",43987),4000)
            socket.soTimeout=6000
            val out=socket.getOutputStream()
            out.write(context.filesDir.resolve("shell-token").readBytes()); out.flush()
            val reader=socket.getInputStream().bufferedReader()
            check(reader.readLine()=="HELLO shell uid=2000") { "Нет подтверждения shell" }
            out.write(mode+100); out.flush()
            check(reader.readLine()=="PERSISTENT mode=$mode lease_ms=5000") { "Обнови помощник через автоматическую настройку" }
            if(earlyEntry) { out.write(203); out.flush() }
            heartbeat.scheduleWithFixedDelay({
                if(!closed && SystemClock.elapsedRealtime()-uiAlive<1500) runCatching { out.write(200); out.flush() }.onFailure { runCatching { socket.close() } }
            },0,1,TimeUnit.SECONDS)
            while(!closed) {
                val value=reader.readLine() ?: break
                line(value)
                if(value.startsWith("DISPLAY error") || value.startsWith("DISPLAY cleanup_error")) { reason=value; retryable=false; break }
                if(value=="DISPLAY finished") { retryable=false; break }
            }
        } catch(e: Exception) { retryable=e is java.io.IOException; reason="Нет сеанса: ${e.message}. Проверь и обнови помощник." }
        finally { val report=!closed; close(); if(report) ended(reason,retryable) }
    }
    override fun close() { closed=true; runCatching { socket.close() }; heartbeat.shutdownNow() }
}
