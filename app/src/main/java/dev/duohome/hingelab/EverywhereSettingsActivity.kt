package dev.duohome.hingelab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.*

/** Opt-in only. Accessibility is enabled by the user, never through shell. */
class EverywhereSettingsActivity : Activity() {
    private lateinit var status: TextView
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if(android.os.Build.VERSION.SDK_INT<33) { Toast.makeText(this,"Для анимации нужен Android 13 или новее",Toast.LENGTH_LONG).show(); finish(); return }
        val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        setContentView(ScrollView(this).apply { addView(column) })
        fun label(s:String) { column.addView(TextView(this).apply { text=s; textSize=16f; setPadding(0,16,0,16) }) }
        fun button(s:String, action:()->Unit) { column.addView(Button(this).apply { text=s; setOnClickListener { action() } }) }
        label("Анимация поверх приложений · эксперимент")
        label("Выбранная схема управления экранами работает без лимита 60 секунд. Во время движения шарнира анимируется снимок текущего приложения. Это не живая деформация его интерфейса; известное мигание системы остаётся.")
        label("Отдельная служба специальных возможностей получает снимки экрана. Снимки остаются только в памяти, не записываются и не отправляются. Защищённые экраны пропускаются. При блокировке, остановке или потере связи анимация снимается. В лаборатории режим приостанавливается, чтобы можно было запускать тесты.")
        label("Кадры внутреннего и внешнего экранов раздельные: сохраняются до 10 секунд в своей настоящей компоновке. Для внешнего кадра сначала нужно увидеть приложение на внешнем экране. Если подходящего кадра нет, эффект пропускается. Один оверлей показывается не дольше 3,5 секунды — при медленном сгибании может исчезнуть раньше завершения движения.")
        status=TextView(this); column.addView(status)
        button("1. Разрешить уведомление с кнопкой остановки") {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),1)
        }
        button("2. Включить «Hinge Lab — анимация поверх приложений»") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("Проверить очистку окон · без переключения экранов") {
            val service=EverywhereAnimationService.instance
            if(service==null) Toast.makeText(this,"Сначала включи службу анимации",Toast.LENGTH_LONG).show()
            else service.verifyWindowCleanup()
        }
        button("Проверить поверх приложений · 86 · 45 секунд") {
            val service=EverywhereAnimationService.instance
            if(service==null || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this,"Включи службу анимации и разреши уведомления",Toast.LENGTH_LONG).show()
            } else {
                service.enable(86,boundedTest=true)
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                finish()
            }
        }
        val selected=intent.getIntExtra("mode",86)
        for(mode in listOf(selected,86,71,88).distinct().filter { it in listOf(71,86,87,88) }) {
            button("Использовать по умолчанию · ${EverywhereAnimationService.name(mode)}") {
                val service=EverywhereAnimationService.instance
                if(service==null || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this,"Сначала включи службу и разреши уведомления",Toast.LENGTH_LONG).show()
                } else AlertDialog.Builder(this).setTitle("Включить постоянный режим?")
                    .setMessage("${EverywhereAnimationService.name(mode)}. Нужен обновлённый shell-помощник: после установки APK заново нажми «Запустить помощник · автоматически».\n\n"+
                        if(mode==71) "Классический 0.18 сохраняет оба экрана включёнными, в том числе внутри сложенного телефона. Это расходует заряд. Защита от повторных отмен системой остаётся и может остановить режим."
                        else "Схема переключений и импульс питания 700 мс сохранены. В полностью закрытом и раскрытом состоянии запрос двух экранов освобождается. Работа в фоне расходует заряд; автономность пока не измерена.")
                    .setNegativeButton("Отмена",null).setPositiveButton("Включить") { _,_ ->
                        service.enable(mode)
                        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                        finish()
                    }.show()
            }
        }
        button("Остановить постоянную анимацию") { EverywhereAnimationService.stop(this); refresh() }
        button("Скопировать журнал постоянного режима") {
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Hinge Lab",EverywhereAnimationService.report()))
            Toast.makeText(this,"Журнал скопирован",Toast.LENGTH_SHORT).show()
        }
    }
    override fun onResume() { super.onResume(); EverywhereAnimationService.instance?.pauseForLab(); if(::status.isInitialized) refresh() }
    private fun refresh() { status.text="Служба: ${if(EverywhereAnimationService.instance!=null) "включена" else "не включена"}\n${EverywhereAnimationService.status}" }
}
