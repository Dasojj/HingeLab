package dev.duohome.hingelab

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.*

object PanelProbeResults {
    val frames=mutableListOf<Pair<String,Bitmap>>()
    val events=mutableListOf<String>()
    fun clear() { frames.clear(); events.clear() }
    fun event(value:String) { if(events.size<80) events.add(value) }
}

class PanelProbeActivity:Activity() {
    override fun onCreate(state:Bundle?) { super.onCreate(state); render() }
    override fun onResume() { super.onResume(); EverywhereAnimationService.instance?.pauseForLab(); render() }
    private fun render() {
        val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        setContentView(ScrollView(this).apply { addView(column) })
        fun label(value:String) { column.addView(TextView(this).apply { text=value; textSize=16f; setPadding(0,12,0,12) }) }
        fun button(value:String,action:()->Unit) { column.addView(Button(this).apply { text=value; setOnClickListener { action() } }) }
        label("Проверка внешней компоновки · отдельно от основной анимации")
        label("Раскрой телефон. Начни проверку, открой нужное приложение и медленно согни до 100–120°, задержись на 8 секунд, затем раскрой. Режим 4 включится при угле ≤150°. Полностью закрывать не нужно. Через 45 секунд проверка остановится. Вернись сюда за результатом.")
        label("На внешнем экране должна появиться бирюзовая плашка на 2,5 секунды. После неё остаётся прозрачное окно 1px с KEEP_SCREEN_ON только на время снимков. Проверь глазами, загорается ли плашка. Без переноса приложения. Снимаем обе панели по очереди с интервалом 650 мс: три пары после включения второго экрана. Миниатюры только в памяти, не сохраняются в файлы и не отправляются. Наличие снимка не доказывает, что приложение отрисовало вторую компоновку — сравни содержимое. Стандартное мигание возможно.")
        button("Начать проверку двух компоновок · 45 секунд") {
            val service=EverywhereAnimationService.instance
            if(service==null || !getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                startActivity(Intent(this,EverywhereSettingsActivity::class.java))
            } else {
                service.enable(86,boundedTest=true,panelDiagnostic=true)
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                finish()
            }
        }
        button("Остановить проверку") { EverywhereAnimationService.stop(this); render() }
        button("Удалить результаты из памяти") { PanelProbeResults.clear(); render() }
        label("Предыдущий постоянный режим не возобновляется автоматически. Для него используй большую кнопку на главной.")
        label(PanelProbeResults.events.joinToString("\n").ifEmpty { "Результатов пока нет" })
        PanelProbeResults.frames.forEach { (caption,bitmap) ->
            label(caption)
            column.addView(ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds=true },LinearLayout.LayoutParams(-1,-2))
        }
    }
}
