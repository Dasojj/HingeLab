package dev.duohome.hingelab
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.widget.*
class FallbackProbeActivity:Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val fallback=intent.getIntExtra("fallback",1).coerceIn(1,2)
        val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        setContentView(ScrollView(this).apply { addView(column) })
        column.addView(TextView(this).apply { textSize=18f; text=if(fallback==1)
            "Обрезка половины внутреннего кадра без растяжения. Только при складывании, если актуального внешнего снимка нет. Сохранённый внешний кадр всегда имеет приоритет. Обрезка не сохраняется как настоящая внешняя компоновка."
            else "Перенос текущей обычной задачи на display 1 с возвратом через 2 секунды. Одна попытка на цикл складывания; повторная — после подтверждённого возврата и полного закрытия либо раскрытия, при складывании и отсутствии внешнего кадра. Внутри остаётся снимок, а не живое приложение. Система может запретить перенос; приложение может изменить компоновку или пересоздаться. Не используй несохранённый документ. При отказе ограничений системы не меняем." })
        column.addView(TextView(this).apply { text="Запусти, открой приложение на внутреннем экране и медленно согни до 100–120°, задержись на 3 секунды и раскрой. Тест остановится через 45 секунд. Основная кнопка не меняется." })
        column.addView(Button(this).apply { text="Начать отдельную проверку · 45 секунд"; setOnClickListener {
            val service=EverywhereAnimationService.instance
            if(service==null || !getSystemService(NotificationManager::class.java).areNotificationsEnabled()) startActivity(Intent(this@FallbackProbeActivity,EverywhereSettingsActivity::class.java))
            else { service.enable(86,boundedTest=true,fallbackExperiment=fallback); startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)); finish() }
        } })
        column.addView(Button(this).apply { text="Остановить"; setOnClickListener { EverywhereAnimationService.stop(this@FallbackProbeActivity) } })
        column.addView(TextView(this).apply { text=EverywhereAnimationService.report() })
    }
    override fun onResume() { super.onResume(); EverywhereAnimationService.instance?.pauseForLab() }
}
