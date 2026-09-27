package dev.duohome.hingelab

import android.content.Context
import android.util.Base64
import com.flyfishxu.kadb.Kadb

object HelperBootstrap {
    fun start(context: Context, port: Int, turnOff: Boolean): String {
        check(android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0)==1) {
            "Включи «Отладка по USB» в параметрах разработчика и повтори запуск. Кабель не нужен; переключатель оставь включённым."
        }
        val connection = Kadb.create("127.0.0.1", port, connectTimeout = 5000)
        try {
            val key = Base64.encodeToString(context.filesDir.resolve("shell-token").readBytes(), Base64.NO_WRAP)
            val script = context.assets.open("start-wireless-helper.sh").bufferedReader().use { it.readText() }.replace("__TOKEN_BASE64__", key)
            val output = connection.shell(script).allOutput.trim()
            check(output.lineSequence().any { it.startsWith("READY uid=2000 pid=") }) { "Помощник не подтвердил запуск" }
            // User-started setup provisions future local restarts; no arbitrary package input.
            val grant = connection.shell("pm grant dev.duohome.hingelab android.permission.WRITE_SECURE_SETTINGS").allOutput.trim()
            if (turnOff) connection.shell("nohup sh -c 'sleep 2; settings put global adb_wifi_enabled 0' </dev/null >/dev/null 2>&1 &")
            return output.take(250) + if(grant.isEmpty()) "\nПовторный запуск подготовлен" else "\nПраво повторного запуска не выдано; потребуется настройка через меню"
        } finally { connection.close() }
    }
}
