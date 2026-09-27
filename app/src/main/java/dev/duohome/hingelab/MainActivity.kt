package dev.duohome.hingelab

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.*
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import com.flyfishxu.kadb.mdns.*
import kotlinx.coroutines.*
import okio.Path.Companion.toPath
import java.net.NetworkInterface
import java.util.Locale

class MainActivity : Activity(), SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var sensors: SensorManager
    private var mdns: KadbMdnsAndroid? = null
    private var probe: java.io.Closeable? = null
    private var generation = 0
    private var visible = false
    private var shellWanted = false
    private var launchingHelper = false
    private lateinit var disableWireless: CheckBox
    private var rawAngle: Float? = null
    private var privateAngle: Float? = null
    private var received = 0L
    private var publicReceived = 0L
    private var samples = 0
    private var sampleStart = 0L
    private val distinct = sortedSetOf<Float>()
    private val history = ArrayDeque<String>()
    private val diagnostics = ArrayDeque<String>()
    private lateinit var diagnosticText: TextView
    private var startedAt = 0L
    private var readiness=Readiness()
    private lateinit var readinessText: TextView
    private var audit: ContextAudit?=null
    private var testWakeLock: PowerManager.WakeLock?=null
    private fun releaseTestWakeLock() { testWakeLock?.let { if(it.isHeld) it.release() }; testWakeLock=null }
    private var testUntil=0L
    private lateinit var auditText: TextView
    private val auditTick=object: Runnable {
        override fun run() {
            if(testUntil==0L) return
            audit?.observe(auditContext())
            getSharedPreferences("lab",MODE_PRIVATE).edit().putString("audit", "Незавершённый тест (промежуточный результат)\n"+audit?.report()).apply()
            if(SystemClock.elapsedRealtime()>=testUntil) {
                testUntil=0; releaseTestWakeLock(); setShowWhenLocked(false)
                if(!visible) stopProbe()
                getSharedPreferences("lab",MODE_PRIVATE).edit().putString("audit",audit?.report()).apply()
                return
            }
            handler.postDelayed(this,500)
        }
    }
    private lateinit var animationStatus: TextView
    private lateinit var root: LinearLayout
    private lateinit var publicText: TextView
    private lateinit var privateText: TextView
    private lateinit var stats: TextView
    private lateinit var state: TextView
    private lateinit var events: TextView
    private lateinit var pairPort: EditText
    private lateinit var connectPort: EditText
    private lateinit var code: EditText
    private lateinit var gauge: Gauge
    private var publicName = "Датчик не найден"
    private val ticker = object : Runnable {
        override fun run() { render(); if (visible) handler.postDelayed(this, 100) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EverywhereAnimationService.instance?.pauseForLab()
        // Samsung FoldInteractive subscribes only while visible. Request the
        // normal wallpaper behind our opaque diagnostic UI to keep its engine
        // active; never change the user's wallpaper or physical display state.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        val secretFile = filesDir.resolve("shell-token")
        if (!secretFile.exists()) secretFile.writeBytes(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        sensors = getSystemService(SensorManager::class.java)
        KadbCert.configure(OkioFilePrivateKeyStore(filesDir.resolve("adb_key.pem").path.toPath()))
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(17,24,32)); isFillViewport = true }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(16),dp(20),dp(24)) }
        scroll.addView(root)
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom)); insets
        }
        scroll.requestApplyInsets()
        label("Hinge Lab · ${packageManager.getPackageInfo(packageName,0).versionName}", 28)
        label("Исследовательский инструмент · без root", 15, muted)
        label("Точный угол Samsung, управление двумя экранами и архив проверенных гипотез. Проверено на SM-F971B / Android 17; совместимость с другими прошивками не гарантирована. Мигание при смене экранов не устранено.",14,muted)
        button("Проверить внешнюю компоновку · отдельный эксперимент") { stopProbe(); startActivity(Intent(this,PanelProbeActivity::class.java)) }
        button("Эксперимент · обрезка при отсутствии внешнего кадра") { startActivity(Intent(this,FallbackProbeActivity::class.java).putExtra("fallback",1)) }
        button("Эксперимент · перенос окна при отсутствии внешнего кадра") { startActivity(Intent(this,FallbackProbeActivity::class.java).putExtra("fallback",2)) }
        label("Начать работу",21)
        button("1. Подготовить телефон") { showPreparation() }
        button("Специальные возможности Hinge Lab") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("2. Запустить помощник · автоматически") { showSetupConsent() }
        button("3. Посмотреть анимацию складывания") {
            if(Build.VERSION.SDK_INT<33) message("Для эффекта нужен Android 13 или новее")
            else { testUntil=0; releaseTestWakeLock(); handler.removeCallbacks(auditTick); setShowWhenLocked(false); stopProbe(); startActivity(Intent(this,DemoActivity::class.java)) }
        }
        button("Подключиться к уже работающему помощнику") { startProbe(shell=true) }
        button("Анимация поверх приложений · постоянный режим") { stopProbe(); startActivity(Intent(this,EverywhereSettingsActivity::class.java)) }
        button("Остановить помощник и измерения") { shutdownHelper() }
        label("Рекомендуемые варианты",21)
        label("86 — лучший визуальный результат наших тестов. 71 — классическая схема 0.18 для сравнения. Обе проверки ограничены 60 секундами.",14,muted)
        val homeRoot=root
        for ((mode,title) in listOf(86 to "Рекомендуем · режим 4 + питание 700 мс",71 to "Классический 0.18 · сравнение",88 to "Режим 4 · окно без Presentation",87 to "Режим 4 · питание и непрерывный кадр",85 to "Постоянный режим 5 · оба экрана · 60 секунд",84 to "Режим 4 · события без опроса dumpsys",83 to "Режим 4 · прямой запрос как у камеры",81 to "0.18 · сравнение без power-reset",82 to "Режим 4 по движению · TENT → 178°",79 to "Внешний ↔ оба · режим 5 · 60 секунд",77 to "Раннее раскрытие CLOSED → TENT · новая анимация",78 to "0.18 · мягкий уход за край · новая анимация",76 to "0.18 · содержимое в кадре · 55–180°",74 to "0.18 · правая половина 55–175°",72 to "0.18 + передача изображения Compat · 60 секунд",70 to "Способ Z Fold Duo · 60 секунд",69 to "Наш способ 0.19 · ранняя передача")) {
            if(mode==88) {
                val archive=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; visibility=View.GONE }
                button("Архив экспериментов ▾") { archive.visibility=if(archive.visibility==View.GONE) View.VISIBLE else View.GONE }
                root.addView(archive); root=archive
                label("Варианты для воспроизведения исследования. Это не список улучшений: часть гипотез не подтвердилась.",14,muted)
            }
            button(title) {
                val description=if(mode==88) "60 секунд. Заменяем Presentation обычным окном на втором дисплее, сохраняя тип окна и источник угла. Изменение роли дисплея само по себе больше не закрывает наше окно. Удаление дисплея, выход и окончание теста по-прежнему очищают окно. Питание и анимация как в режиме 87. Новые разрешения не нужны. В журнал добавлены события окна; сравниваем вспышку обоев и чёрный провал."
                else if(mode==87) "Проверка на 60 секунд: готовим изображение второго окна до показа, сохраняем прежнее окно до первого кадра замены (максимум 1200 мс). Удержание питания как в 0.37: один импульс 700 мс перед раскрытием и при угле ≤15°, без дополнительного продления после закрытия. Источник угла остаётся активным за непрозрачным изображением демо. Обои не меняем. Проверяем отдельно чёрный провал и появление системных обоев. Завершение возвращает обычный режим экранов."
                else if(mode==86) "Рабочий режим 4 с точным углом и событиями вместо опроса. Перед включением двух экранов из TENT и один раз при свежем угле ≤15° кратко удерживаем питание обоих логических дисплеев, потому что их номера меняются при переходе. Импульс автоматически истекает через 700 мс, без продления в цикле. При очень медленном закрытии импульс может закончиться раньше складывания. Тест 60 секунд. Обои не меняем. Проверяем мигание, не обещаем его устранение."
                else if(mode==85) "Диагностика без анимации и без получения угла. Начни со сложенного телефона: внешний останется главным, внутренний будет включён даже при полном закрытии — только в рамках минутного теста. При системной отмене попробуем восстановить режим один раз. На обоих экранах есть кнопка проверки касаний и выход. Обои не меняются. При выходе на раскрытом телефоне возможен системный переход. Проверь мигание при закрытии и повторном раскрытии."
                else if(mode==84) "Новый режим: физическое положение и подтверждение режима получаем через системные события, без повторного dumpsys. При полном закрытии отпускаем режим двух экранов. Тест 60 секунд; прежний режим 83 сохранён для сравнения. state_confirmed_ms — подтверждение режима, не время появления картинки. Системное мигание всё ещё возможно."
                else if(mode==83) "Схема по углу/TENT как в режиме 82. Режим №4 запрашиваем напрямую с флагом камеры Samsung, без отдельных команд питания экранов. Это эксперимент, не гарантия отсутствия моргания. Проверка длится 60 секунд, при выходе запрос отменяется. Сравни задержку включения внешнего и моргание при полном складывании и начале раскрытия."
                    else if(mode==81) "Классическая последовательность 0.18, но без команды power-reset. Сравни моргание и возвращение живого угла после полного складывания. Проверка — 60 секунд."
                    else if(mode==82) "В обычном раскрытом состоянии ждём угла 150° и включаем режим №4. При полном закрытии возвращаем обычный режим, а при TENT снова включаем №4. На свежем угле 178° возвращаем обычный внутренний экран. Без power-reset; при неожиданной отмене режима не включаем его по кругу. Проверка — 60 секунд. Плавность ещё не подтверждена."
                    else if(mode==79) "Запусти на внешнем экране сложенного телефона. При раскрытии включим внутренний вторым, сохраняя внешний главным. При закрытии уберём только внутренний. Переключения в режим 4 и команды power-reset здесь нет. Если начать на разложенном телефоне, сначала ждём полного складывания. Через 60 секунд или при выходе восстановим обычный режим. При завершении на открытом телефоне возможен отдельный системный переход — его проверяем отдельно от складывания."
                    else if(mode==77) "На сложенном телефоне ждём начала раскрытия. По состоянию TENT один раз включаем два экрана; при закрытии возвращаем обычный режим, без повторного включения внутреннего экрана. Новая правая проекция 55–178°. Эксперимент на 60 секунд: скорость запуска и мигание ещё проверяем."
                    else if(mode==78) "Классическое управление 0.18 с новой внутренней анимацией: мягкий уход за край, перспектива и размытие. Внешняя анимация прежняя. Через минуту проверка завершится."
                    else if(mode==76) "Новая художественная проекция внутренней правой половины: её содержимое остаётся в кадре, перспектива и размытие ослабевают от 55° до 180°. Внешний эффект прежний. Управление экранами — классический 0.18, мигание пока остаётся. Проверка ограничена минутой."
                    else if(mode==74) "Классический способ 0.18 с изменением только внутренней анимации: правая половина, плавное раскрытие от 55° до 175°. При складывании эффект идёт обратно. Внешняя анимация и управление экранами прежние; мигание пока остаётся. Проверка завершится через минуту или при выходе."
                    else if(mode==72) "Восстановление экранов и угла — из 0.18. Из Compat перенесены сохранение кадра физического экрана и плавное догоняющее движение проекции после переключения. Изображение остаётся демонстрационным. Оба экрана включены на время проверки; мигание системы пока возможно. Через минуту или при выходе вернём обычный режим."
                    else if(mode==70) "Повторим цепочку Z Fold Duo: пробуждение обоев, восстановление после каждой отписки и переключение экранов по углу. Сначала запусти на сложенном телефоне и медленно раскрой. Ответы скрытых обоев тоже видны; постоянный угол не означает работающий датчик. Через минуту или при выходе проверка завершится. Это перенос логики датчика и экранов; изображение остаётся демо Lab."
                    else "Временно включим оба экрана. Способ 0.18 восстанавливает угол с миганием. В 0.19 сохраняем раннюю передачу наружу и известное зависание для сравнения. Через минуту или при выходе вернём обычный режим."
                android.app.AlertDialog.Builder(this).setTitle(title).setMessage("Что проверяет\n"+ResearchModes.summary(mode)+"\n\nКак запустить\n"+description)
                    .setNegativeButton("Отмена",null).setPositiveButton("Начать") { _,_ ->
                        testUntil=0; releaseTestWakeLock(); handler.removeCallbacks(auditTick); setShowWhenLocked(false)
                        shellWanted=false; stopProbe()
                        startActivity(Intent(this,DemoActivity::class.java).putExtra("dualDisplay",true).putExtra("experimentMode",if(mode==74 || mode==76 || mode==78) 71 else mode).putExtra("earlyInner",mode==74 || mode==76 || mode==77 || mode==78 || mode==79).putExtra("boundedInner",mode==76).putExtra("softInner",mode==77 || mode==78 || mode==79))
                    }.show()
            }
        }
        button("Раннее открытие · пассивная запись") {
            testUntil=0; releaseTestWakeLock(); handler.removeCallbacks(auditTick); shellWanted=false; stopProbe()
            startActivity(Intent(this,PostureActivity::class.java))
        }
        button("Предпросмотр новой анимации · без переключения экранов") {
            shellWanted=false; stopProbe()
            startActivity(Intent(this,DemoActivity::class.java).putExtra("earlyInner",true).putExtra("softInner",true).putExtra("manualStart",true))
        }
        button("Предпросмотр Samsung · отдельная гипотеза") { startActivity(Intent(this,WallpaperProbeActivity::class.java)) }
        label("Предпросмотр проверяет жизненный цикл системных обоев. Чтение угла на внешнем экране этим способом устойчиво не подтверждено. Обои не устанавливаются.",13,muted)
        root=homeRoot
        label("Измерение и совместимость",21)
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18),dp(14),dp(18),dp(14)); background = background(0xff1c2935.toInt()) }
        root.addView(card, LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(20) })
        privateText = text("—", 52, accent); card.addView(privateText)
        card.addView(text("Samsung · экспериментальный источник", 14, muted))
        publicText = text("Android: —", 23, Color.WHITE); card.addView(publicText)
        gauge = Gauge(this); card.addView(gauge, LinearLayout.LayoutParams(-1,dp(100)))
        stats = text("", 13, muted); card.addView(stats)
        state = label("Помощник можно запустить с этого телефона. Беспроводная отладка нужна только на время запуска; после перезагрузки запуск нужно повторить.", 15)
        label("Показания без сглаживания. Если данные устарели, это отмечается явно. Рисунок показывает полученное значение, а не оценку по движению телефона.", 13, muted)
        readinessText=label("",14,muted)
        button("Проверить фон и блокировку · 60 секунд") { showLabTest() }
        auditText=label(getSharedPreferences("lab",MODE_PRIVATE).getString("audit",null) ?: "Фон, блокировка и внешний экран ещё не проверены.",13,muted)
        button("Остановить измерение и тест") {
            testUntil=0; releaseTestWakeLock(); handler.removeCallbacks(auditTick); setShowWhenLocked(false)
            shellWanted=false; stopProbe(); setStatus("Измерение остановлено")
        }
        button("Остановить автоматическую настройку") { SetupAccessibilityService.instance?.cancel(); setStatus("Настройка остановлена") }
        val mainRoot=root
        val advanced=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; visibility=View.GONE }
        button("Ручная настройка и прежние режимы ▾") { advanced.visibility=if(advanced.visibility==View.GONE) View.VISIBLE else View.GONE }
        mainRoot.addView(advanced); root=advanced
        button("Прежний режим с запросом журнала") { startProbe(local=true) }
        label("Локальный режим: однократно выдать READ_LOGS с ПК по USB. Android может дополнительно спросить разрешение на чтение журнала. Беспроводная отладка для этого режима не нужна. Ниже оставлен прежний способ для сравнения.", 14, muted)
        label("Запуск помощника без компьютера", 21)
        label("1. Включи Wi-Fi и «Беспроводную отладку» в параметрах разработчика.\n2. Открой Hinge Lab и Настройки рядом, в двух окнах. В Настройках выбери «Сопряжение с помощью кода» и оставь диалог открытым.\n3. Введи код здесь и нажми «Сопрячь». Порт обычно подставляется сам.\n4. Закрой диалог сопряжения. На основной странице отладки другой порт — подключения. Проверь его ниже и начни измерение.", 14, muted)
        button("Открыть параметры разработчика") {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        pairPort = field("Порт сопряжения (из диалога с кодом)")
        code = field("Код сопряжения · 6 цифр").apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        button("Сопрячь с этим телефоном") { pair() }
        connectPort = field("Порт подключения (на странице отладки)")
        disableWireless = CheckBox(this).apply {
            text="Выключить беспроводную отладку после запуска помощника"
            setTextColor(Color.WHITE); isChecked=true
        }
        root.addView(disableWireless)
        button("Запустить помощник без ПК") { launchHelper(); scroll.smoothScrollTo(0,0) }
        label("Сопряжение обычно сохраняется. После перезагрузки включи беспроводную отладку, вернись сюда и нажми «Запустить помощник без ПК». Повторный код нужен, если телефон забыл сопряжение. При возврате с Home измерение возобновляется автоматически, пока помощник работает.", 14, muted)
        label("Прежний способ: постоянное ADB-соединение", 21)
        button("Подключить и начать измерение") { startProbe(); scroll.smoothScrollTo(0,0) }
        label("Подключение только к этому телефону: 127.0.0.1. Этот ручной режим не использует внешние серверы, root или запись экрана. При уходе из приложения измерение Samsung останавливается; после возврата нажми «Начать».", 13, muted)
        root=mainRoot
        val reports=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; visibility=View.GONE }
        button("Показания и журнал подключения ▾") { reports.visibility=if(reports.visibility==View.GONE) View.VISIBLE else View.GONE }
        root.addView(reports); root=reports
        label("Последние изменения", 21)
        events = label("Пока нет показаний Samsung", 13, muted)
        label("Диагностика подключения", 21)
        diagnosticText = label("Измерение Samsung ещё не запускалось", 13, muted)
        root=mainRoot
        button("Скопировать результат проверки") {
            val report = "Hinge Lab ${packageManager.getPackageInfo(packageName,0).versionName}\n${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}\n${state.text}\n${privateText.text}\n${publicText.text}\n${stats.text}\n${readinessText.text}\n${auditText.text}\n${events.text}\nДиагностика подключения:\n${diagnostics.joinToString("\n")}"
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Hinge Lab", report))
            Toast.makeText(this,"Результат скопирован",Toast.LENGTH_SHORT).show()
        }
        button("Исходники и лицензии") {
            AlertDialog.Builder(this).setTitle("Hinge Lab · GPL-3.0")
                .setMessage(assets.open("NOTICE.txt").bufferedReader().use { it.readText() })
                .setNeutralButton("Полные лицензии") { _, _ ->
                    val files = assets.list("licenses").orEmpty().sorted()
                    AlertDialog.Builder(this).setTitle("Лицензии компонентов")
                        .setItems(files.toTypedArray()) { _, index ->
                            AlertDialog.Builder(this).setTitle(files[index])
                                .setMessage(assets.open("licenses/${files[index]}").bufferedReader().use { it.readText() })
                                .setPositiveButton("Закрыть", null).show()
                        }.setNegativeButton("Закрыть", null).show()
                }
                .setPositiveButton("Закрыть",null).show()
        }
        installSections(scroll)
        startDiscovery()
    }

    private fun installSections(scroll:ScrollView) {
        val laboratory=root
        scroll.removeView(laboratory)
        val container=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        scroll.addView(container)
        val tabs=LinearLayout(this).apply { setPadding(dp(20),dp(12),dp(20),0) }
        container.addView(tabs)
        val animation=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(12),dp(20),dp(24)) }
        container.addView(animation); container.addView(laboratory)
        root=animation
        label("Hinge Lab",30)
        label("${packageManager.getPackageInfo(packageName,0).versionName}  ·  Анимация складывания",16,accent)
        label("Экспериментальная анимация перехода между экранами поверх приложений. Для Samsung Fold — без root. Мигание при переключении остаётся.",16,muted)
        animationStatus=label("",14,muted)
        val stylePrefs=getSharedPreferences("everywhere",MODE_PRIVATE)
        val styleLabel=label(if(stylePrefs.getBoolean("perspective_window",false)) "Стиль: перспектива + блюр · эксперимент" else "Стиль: текущая анимация",14,accent)
        button("Выбрать стиль анимации") {
            val choices=arrayOf("Текущая анимация", "Перспектива + блюр · эксперимент")
            AlertDialog.Builder(this).setTitle("Отрисовка анимации")
                .setSingleChoiceItems(choices,if(stylePrefs.getBoolean("perspective_window",false)) 1 else 0) { dialog,index ->
                    stylePrefs.edit().putBoolean("perspective_window",index==1).apply()
                    styleLabel.text="Стиль: ${choices[index]}"
                    dialog.dismiss()
                    message("Стиль применится к следующему переходу. Запуск и остановка — прежними кнопками.")
                }.setNegativeButton("Закрыть",null).show()
        }
        button("Включить анимацию",prominent=true) { enableEverywhere() }
        button("Остановить анимацию") { EverywhereAnimationService.stop(this); message("Анимация остановлена") }
        label("Первый запуск",21)
        label("Пройди подготовку один раз. После перезагрузки телефона помощник и анимация запускаются повторно.",14,muted)
        label("Важно: «Отладка по USB» должна оставаться включённой, пока используешь анимацию. Подключать кабель не нужно. Wi-Fi-отладка после запуска выключается автоматически.",15,accent)
        button("1. Подготовить телефон") { showPreparation() }
        button("2. Запустить помощник · автоматически") { showSetupConsent() }
        button("3. Включить службу анимации") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        label("В специальных возможностях выбери «Hinge Lab — анимация поверх приложений».",13,muted)
        button("4. Разрешить уведомление с остановкой") {
            if(Build.VERSION.SDK_INT>=33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),2086)
        }
        val details=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; visibility=View.GONE }
        button("Как работает и что важно знать ▾") { details.visibility=if(details.visibility==View.GONE) View.VISIBLE else View.GONE }
        animation.addView(details); root=details
        label("Анимация использует снимки текущего приложения. Они остаются в памяти и не отправляются. Если внешнего кадра нет, окно ненадолго переносится на внешний экран и возвращается. Защищённое содержимое не обходим.",14,muted)
        label("Беспроводная отладка нужна для запуска помощника и затем выключается. Режим работает без таймера, пока ты его не остановишь. На блокировке и внутри Hinge Lab — пауза. Защита снимает эффект при сбое.",14,muted)
        label("Некоторые приложения не успевают подготовить внешний кадр: во время складывания внешний экран может оставаться чёрным до полного закрытия. Мигание при переключении экранов остаётся. Быстрое складывание может пройти без видимой внешней анимации; долгий жест — без завершения эффекта. Совместимость проверена на SM-F971B / Android 17, автономность ещё не измерена.",14,muted)
        button("Остановить помощник и все измерения") { shutdownHelper() }
        root=animation
        label("Сообщество",21)
        button("Посмотрим на практике · канал") { openCommunityLink("https://t.me/pnplab") }
        button("Обсудить и предложить идею · чат") { openCommunityLink("https://t.me/pnplab_chat") }
        root=laboratory
        val userTab=Button(this).apply { text="Анимация"; isAllCaps=false }
        val labTab=Button(this).apply { text="Лаборатория"; isAllCaps=false }
        tabs.addView(userTab,LinearLayout.LayoutParams(0,dp(52),1f))
        tabs.addView(labTab,LinearLayout.LayoutParams(0,dp(52),1f))
        fun selectLab(selected:Boolean) {
            animation.visibility=if(selected) View.GONE else View.VISIBLE
            laboratory.visibility=if(selected) View.VISIBLE else View.GONE
            userTab.setTextColor(Color.WHITE); labTab.setTextColor(Color.WHITE)
            userTab.background=background(if(selected) 0xff243746.toInt() else 0xff087f72.toInt())
            labTab.background=background(if(selected) 0xff087f72.toInt() else 0xff243746.toInt())
            scroll.post { scroll.scrollTo(0,0) }
        }
        userTab.setOnClickListener { selectLab(false) }; labTab.setOnClickListener { selectLab(true) }
        selectLab(false)
    }

    private fun openCommunityLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Сообщество Hinge Lab", url))
            Toast.makeText(this,"Нет приложения для открытия ссылки. Адрес скопирован.",Toast.LENGTH_LONG).show()
        }
    }

    private fun shutdownHelper() {
        EverywhereAnimationService.stop(this)
        testUntil=0; releaseTestWakeLock(); handler.removeCallbacks(auditTick); setShowWhenLocked(false)
        shellWanted=false; stopProbe(); SetupAccessibilityService.instance?.cancel(); mdns?.stop()
        getSharedPreferences("setup",MODE_PRIVATE).edit().remove("start_measurement").apply()
        scope.launch {
            val result=withContext(Dispatchers.IO) {
                try {
                    java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress("127.0.0.1",43987),3000); socket.soTimeout=5000
                        socket.getOutputStream().apply { write(filesDir.resolve("shell-token").readBytes()); flush() }
                        val reader=socket.getInputStream().bufferedReader()
                        check(reader.readLine()=="HELLO shell uid=2000") { "Неизвестный помощник" }
                        socket.getOutputStream().apply { write(254); flush() }
                        check(reader.readLine()=="STOPPED") { "Помощник не подтвердил остановку. Обнови его или перезагрузи телефон." }
                        "Помощник остановлен. Для следующего опыта запусти его заново."
                    }
                } catch(e: java.net.ConnectException) { "Помощник уже не запущен." }
                catch(e: Exception) { "Остановка не подтверждена: ${e.message}" }
            }
            setStatus(result)
        }
    }

    private fun showPreparation() {
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(20),dp(8),dp(20),dp(12))
        }
        fun note(value:String) { content.addView(text(value,15,Color.WHITE)) }
        fun action(value:String,block:()->Unit) {
            content.addView(Button(this).apply {
                text=value; isAllCaps=false; gravity=Gravity.START or Gravity.CENTER_VERTICAL
                minHeight=dp(52); setPadding(dp(12),dp(8),dp(12),dp(8))
                setTextColor(Color.WHITE); background=background(0xff243746.toInt())
                setOnClickListener { block() }
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6); bottomMargin=dp(10) })
        }
        note("Подключись к Wi-Fi. Если параметры разработчика ещё выключены: «Сведения о телефоне» → «Сведения о ПО» → нажми «Номер сборки» 7 раз. Подтверди PIN самостоятельно.")
        action("Открыть сведения о телефоне") { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
        note("Обязательно перед запуском помощника: в параметрах разработчика включи «Отладка по USB» и подтверди системное предупреждение. Оставляй этот переключатель включённым, пока пользуешься анимацией. Кабель и компьютер не нужны. Без этого после автоматического выключения Wi-Fi-отладки система может завершить помощник. Разрешай подключение только своим компьютерам.")
        action("Открыть параметры разработчика · отладка по USB") { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        note("1. Попробуй включить службу\nВ специальных возможностях открой «Установленные приложения» → «Hinge Lab — настройка помощника». Эта служба помогает пройти настройку после твоей команды.")
        note("Android может показать «Доступ для приложения запрещён» или «Ограниченные настройки». Если это произошло, закрой предупреждение и вернись сюда кнопкой «Назад». Это окно с шагами останется открытым. Если служба включилась, шаг 2 пропусти.")
        action("1. Открыть специальные возможности") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        note("2. Если Android заблокировал доступ\nОткрой сведения о Hinge Lab кнопкой ниже. Справа сверху нажми ⋮ → «Разрешить ограниченные настройки». Подтверди действие отпечатком или кодом блокировки в системном окне. Hinge Lab этот код не запрашивает и не вводит за тебя. Разрешай доступ, только если доверяешь установленной сборке.")
        action("2. Открыть сведения о Hinge Lab") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))
        }
        note("Если пункта в меню нет, сначала попробуй включить службу через шаг 1 и дождись предупреждения Android. Затем снова открой сведения о приложении. Если служба уже включается, разрешать ничего дополнительно не нужно.")
        note("3. Вернись к включению службы\nСнова открой «Hinge Lab — настройка помощника» в специальных возможностях и включи её.")
        action("3. Снова открыть специальные возможности") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        note("После включения службы закрой эту подсказку и нажми на главном экране «2. Запустить помощник · автоматически». Дальше выполни шаги 3 и 4 на главном экране, затем включи анимацию. Если доступ к службе анимации тоже заблокирован, используй тот же маршрут через сведения о Hinge Lab.")
        AlertDialog.Builder(this).setTitle("Подготовить телефон")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Вернуться к запуску",null).show()
    }
    private fun hasDirectAccess() = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)==android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun requireUsbDebugging(): Boolean {
        if(Settings.Global.getInt(contentResolver,Settings.Global.ADB_ENABLED,0)==1) return true
        AlertDialog.Builder(this).setTitle("Сначала включи отладку по USB")
            .setMessage("В параметрах разработчика включи «Отладка по USB» и подтверди системное предупреждение. Затем вернись сюда и снова нажми запуск помощника.\n\nКабель не нужен. Переключатель должен оставаться включённым: иначе выключение Wi-Fi-отладки может остановить помощник вместе с системной службой ADB. Hinge Lab не включает USB-отладку за тебя.")
            .setPositiveButton("Открыть настройки") { _,_ -> startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            .setNegativeButton("Отмена",null).show()
        return false
    }
    private fun showSetupConsent() {
        if(!requireUsbDebugging()) return
        AlertDialog.Builder(this).setTitle("Запустить помощник?")
            .setMessage("Для анимации нужен подробный угол шарнира: обычный датчик на этом телефоне передаёт только несколько состояний. Настройка запускает местный помощник и подготавливает последующие запуски. Помощник получает угол шарнира. Сама анимация использует временные снимки экрана в памяти телефона; они не сохраняются в файлы и не отправляются.\n\n" + if(hasDirectAccess()) "Hinge Lab ненадолго включит беспроводную отладку, запустит помощник на этом телефоне и выключит отладку обратно. Переходить в настройки не потребуется.\n\nИспользуется ранее выданное право изменения системных настроек и сохранённое сопряжение. После перезагрузки помощник нужно запустить снова." else "Сейчас Hinge Lab откроет настройки, ненадолго включит беспроводную отладку и запустит местный помощник. После запуска отладка выключится. Если Android попросит подтверждение, сделай его самостоятельно.\n\nПосле поиска нажми подсвеченную строку «Отладка по Wi-Fi», если приложение остановилось на ней. Дальше настройка продолжится автоматически. Оставайся в настройках до завершения. Обычно это занимает несколько секунд. После перезагрузки помощник нужно запустить снова.")
            .setPositiveButton("Начать") { _,_ ->
                if(!requireUsbDebugging()) return@setPositiveButton
                val service=SetupAccessibilityService.instance
                if(hasDirectAccess()) directStart()
                else if(service==null) showPreparation()
                else { testUntil=0; releaseTestWakeLock(); handler.removeCallbacks(auditTick); setShowWhenLocked(false); shellWanted=false; stopProbe(); service.begin() }
            }
            .setNeutralButton("Прочитать подробнее") { _,_ -> showSetupDetails() }
            .setNegativeButton("Отмена",null).show()
    }
    private fun directStart() {
        if(!requireUsbDebugging()) return
        if(launchingHelper) return
        testUntil=0; releaseTestWakeLock(); handler.removeCallbacks(auditTick); setShowWhenLocked(false)
        SetupAccessibilityService.instance?.cancel(); shellWanted=false; stopProbe(); launchingHelper=true
        val wasOn=Settings.Global.getInt(contentResolver,"adb_wifi_enabled",0)==1
        scope.launch {
            try {
                check(Settings.Global.putInt(contentResolver,"adb_wifi_enabled",1)) { "Android отклонил включение отладки" }
                addDiagnostic("Прямой запуск: приложение включило Wi-Fi ADB, uid=${android.os.Process.myUid()}")
                setStatus("Включаем отладку и ищем местный порт…")
                delay(2000)
                var launched=false
                var lastError: Exception?=null
                val deadline=SystemClock.elapsedRealtime()+30000
                repeat(15) {
                    if(!launched && SystemClock.elapsedRealtime()<deadline) {
                        val p=port(connectPort)
                        if(p!=null) {
                            try {
                                withContext(Dispatchers.IO) { HelperBootstrap.start(this@MainActivity,p,true) }
                                launched=true
                            } catch(e: Exception) { if(e is CancellationException) throw e; lastError=e }
                        }
                        if(!launched) delay(700)
                    }
                }
                check(launched) { "Не удалось подключиться. Возможно, нужно первое сопряжение. ${lastError?.javaClass?.simpleName.orEmpty()}" }
                delay(2500)
                Settings.Global.putInt(contentResolver,"adb_wifi_enabled",0)
                check(Settings.Global.getInt(contentResolver,"adb_wifi_enabled",1)==0) { "Отладка не выключилась" }
                addDiagnostic("Прямой запуск завершён: Wi-Fi ADB выключена")
                if(visible) startProbe(shell=true) else shellWanted=true
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { setStatus("Прямой запуск: ${e.message}. Доступ к настройкам не заменяет первое сопряжение.") }
            finally {
                if(!wasOn) runCatching { Settings.Global.putInt(contentResolver,"adb_wifi_enabled",0) }
                launchingHelper=false
            }
        }
    }
    private fun showSetupDetails() {
        AlertDialog.Builder(this).setTitle("Что меняется в телефоне")
            .setMessage("Если отдельно выдано WRITE_SECURE_SETTINGS, приложение само переключает беспроводную отладку. Это широкое право изменения системных настроек, оно сохраняется после перезагрузки и не заменяет первое сопряжение. Право выдаётся через локальное сопряжение при запуске помощника.\n\nПри первом успешном подключении настройка также выдаёт приложению WRITE_SECURE_SETTINGS для последующих запусков без переходов по меню. Это широкое право изменения системных настроек. Код использует его для переключения беспроводной отладки.\n\nБез root и без внешнего сервера: приложение сопрягается с ADB этого же телефона и запускает процесс с правами shell. Это расширенные права, поэтому включай функцию только если доверяешь этой сборке.\n\nСлужба настройки читает только окна приложения Настройки, в том числе одноразовый код сопряжения, и нажимает знакомые пункты после твоей команды. Неизвестные подтверждения не нажимает. Автоматизация прекращается через две минуты.\n\nПомощник слушает только 127.0.0.1; подключение защищено случайным ключом приложения. Он читает ответы об угле из системного журнала и проверяет компонент обоев. Переписку и экран мы не записываем. Но сами права shell шире этой задачи.\n\nПосле успешного запуска беспроводная отладка выключается. Если настройка прервалась, проверь её переключатель: она могла остаться включённой. Сопряжение сохраняется; его можно удалить в настройках отладки. Помощник прекращает работу после перезагрузки. Службу специальных возможностей можно отключить после настройки.\n\nТочный угол зависит от приватного компонента Samsung FoldInteractive и текущих обоев. Чужие прошивки ещё не проверены. Мы не меняем обои автоматически. Демо работает только внутри Hinge Lab.")
            .setPositiveButton("Назад") { _,_ -> showSetupConsent() }.show()
    }
    private fun auditContext(): String {
        val locked=getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        val awake=getSystemService(PowerManager::class.java).isInteractive
        val outer=minOf(resources.displayMetrics.widthPixels,resources.displayMetrics.heightPixels)/resources.displayMetrics.density<600f
        return "${if(visible) "Lab" else "фон"} / ${if(locked) "заблокирован" else "разблокирован"} / ${if(awake) "экран вкл." else "экран выкл."} / ${if(outer) "узкое окно" else "широкое окно"}"
    }
    private fun showLabTest() {
        AlertDialog.Builder(this).setTitle("Проверка фона и блокировки")
            .setMessage("Сначала нажми «Запустить помощник · автоматически», чтобы обновить помощник. Должно появиться «Запросы: из shell, без окна Lab».\n\nВ течение 60 секунд сбор ответов продолжится даже вне Lab. Процессор удерживается активным на время теста; экран может выключаться. Это не тест расхода батареи.\n\n1. Немного подвигай шарнир здесь.\n2. Выйди на Home и снова измени угол.\n3. Вернись в Lab, заблокируй телефон кнопкой питания и разбуди его без разблокировки: тестовое окно сможет показаться поверх блокировки.\n4. Проверь складывание и внешний экран, затем вернись за результатом.\n\nБлокировка не снимается. Если нет свежих углов — это ограничение текущего способа, а не успешный тест. Питанием дисплеев Lab не управляет.")
            .setPositiveButton("Начать 60 секунд") { _,_ ->
                releaseTestWakeLock()
                testWakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"HingeLab:context-test").apply { acquire(65000) }
                audit=ContextAudit(); testUntil=SystemClock.elapsedRealtime()+60000
                setShowWhenLocked(true); startProbe(shell=true)
                handler.removeCallbacks(auditTick); handler.post(auditTick)
            }.setNegativeButton("Отмена",null).show()
    }
    private fun startDiscovery() {
        try {
            val discovery = KadbMdnsAndroid(applicationContext, MdnsConfig(setOf(MdnsServiceType.TLS_CONNECT, MdnsServiceType.TLS_PAIRING)))
            mdns = discovery
            scope.launch {
                discovery.state.collect { found ->
                    val own = NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap { it.inetAddresses.toList() }?.mapNotNull { it.hostAddress?.substringBefore('%') }?.toSet().orEmpty() + "127.0.0.1"
                    found.pairDevices.firstOrNull { it.host.substringBefore('%') in own }?.let {
                        if (!pairPort.hasFocus()) pairPort.setText(it.port.toString())
                    }
                    found.connectDevices.firstOrNull { it.host.substringBefore('%') in own }?.let {
                        if (!connectPort.hasFocus()) connectPort.setText(it.port.toString())
                    }
                }
            }
            discovery.start()
        } catch (_: Exception) { state.text="Автопоиск ADB недоступен. Порты можно ввести вручную." }
    }
    private fun port(field: EditText): Int? = field.text.toString().toIntOrNull()?.takeIf { it in 1..65535 }
    private var pairing = false
    private fun pair() {
        if (pairing) return
        val p = port(pairPort) ?: return message("Введи порт из диалога с кодом сопряжения")
        val secret = code.text.toString()
        if (!secret.matches(Regex("[0-9]{6}"))) return message("Нужен шестизначный код")
        pairing = true; setStatus("Сопряжение…")
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Kadb.pair("127.0.0.1",p,secret,"Hinge Lab") }
                code.text.clear()
                setStatus("Сопряжение выполнено. Проверь порт подключения и нажми «Начать измерение Samsung».")
            } catch (e: Exception) { setStatus("Сопряжение не удалось: ${e.message}. Оставь диалог с кодом открытым в соседнем окне.") }
            finally { pairing=false }
        }
    }
    private fun launchHelper() {
        if(!requireUsbDebugging()) return
        if (launchingHelper) return
        val p = port(connectPort) ?: return message("Включи беспроводную отладку и проверь порт подключения ниже")
        val turnOff = disableWireless.isChecked
        shellWanted=false; stopProbe(); launchingHelper=true
        setStatus("Запускаем помощник с телефона…")
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val connection = Kadb.create("127.0.0.1", p, connectTimeout = 5000)
                    try {
                        val key = android.util.Base64.encodeToString(filesDir.resolve("shell-token").readBytes(), android.util.Base64.NO_WRAP)
                        val script = assets.open("start-wireless-helper.sh").bufferedReader().use { it.readText() }.replace("__TOKEN_BASE64__", key)
                        // Script and key travel over the authenticated local ADB connection; never log them.
                        val output = connection.shell(script).allOutput.trim()
                        check(output.lineSequence().any { it.startsWith("READY uid=2000 pid=") }) { "Помощник не подтвердил запуск: ${output.take(200)}" }
                        if (turnOff) connection.shell("nohup sh -c 'sleep 2; settings put global adb_wifi_enabled 0' </dev/null >/dev/null 2>&1 &")
                        output.take(250)
                    } finally { connection.close() }
                }
                addDiagnostic("Запуск без ПК: $result")
                if (turnOff) { setStatus("Помощник запущен. Выключаем беспроводную отладку…"); delay(3000) }
                if (visible) startProbe(shell = true) else shellWanted=true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { setStatus("Запуск без ПК не удался: ${e.message}. Проверь беспроводную отладку и сопряжение.") }
            finally { launchingHelper=false }
        }
    }
    private fun startProbe(local: Boolean = false, shell: Boolean = false) {
        val p = if (local || shell) 0 else port(connectPort) ?: return message("Введи порт подключения с основной страницы беспроводной отладки")
        shellWanted=shell
        stopProbe()
        startedAt=SystemClock.elapsedRealtime()
        addDiagnostic("Запуск измерения Samsung")
        readiness=Readiness()
        privateAngle=null; received=0; samples=0; sampleStart=0; distinct.clear(); history.clear()
        val token = generation
        val sample: (Float) -> Unit = { angle -> handler.post {
            if (token == generation && (visible || testUntil>SystemClock.elapsedRealtime())) {
                val now = SystemClock.elapsedRealtime()
                if (sampleStart==0L) sampleStart=now
                if (privateAngle != angle) {
                    history.addFirst(String.format(Locale.US,"%tT · %.3f°",System.currentTimeMillis(),angle))
                    while(history.size>16) history.removeLast()
                }
                if(samples==0) addDiagnostic("Первое показание Samsung: $angle°")
                readiness.sample(angle)
                if(testUntil>now) audit?.observe(auditContext(),angle)
                privateAngle=angle; received=now; samples++; distinct.add(angle)
                state.text="Получаем угол Samsung"
            }
        } }
        val status: (String) -> Unit = { value -> handler.post { if(token==generation) setStatus(value) } }
            val diagnostic: (String) -> Unit = { value -> handler.post { if(token==generation) addDiagnostic(value) } }
            val finished: () -> Unit = { handler.post { if(token==generation) { probe=null; addDiagnostic("Поток измерения завершён") } } }
        val next = if (shell) ShellAngleProbe(this, sample, status, diagnostic, finished, health = { value -> handler.post { if(token==generation) { readiness.health(value); if(value=="heartbeat" && testUntil>SystemClock.elapsedRealtime()) audit?.heartbeat(auditContext()) } } }) else if (local) LocalAngleProbe(this, sample, status, diagnostic, finished) else AngleProbe(sample, status, diagnostic, finished)
        probe=next
        Thread({ if (next is ShellAngleProbe) next.run() else if (next is LocalAngleProbe) next.run() else (next as AngleProbe).run(p) },"hinge-lab-probe").start()
    }
    private fun stopProbe() {
        generation++
        val previous=probe; probe=null
        if(previous!=null) Thread({ previous.close() },"hinge-lab-stop").start()
    }
    override fun onConfigurationChanged(config: android.content.res.Configuration) {
        super.onConfigurationChanged(config)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        if(shellWanted && !launchingHelper && (visible || testUntil>SystemClock.elapsedRealtime())) {
            received=0; startProbe(shell=true)
        }
    }
    override fun onStart() {
        EverywhereAnimationService.instance?.pauseForLab()
        super.onStart(); visible=true
        val hinge=sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        publicName=hinge?.name ?: "Датчик отсутствует"
        if(hinge!=null && !sensors.registerListener(this,hinge,SensorManager.SENSOR_DELAY_GAME)) publicName="Подписка отклонена"
        handler.post(ticker)
        val setupPrefs=getSharedPreferences("setup",MODE_PRIVATE)
        setupPrefs.getString("status",null)?.let { setStatus(it); setupPrefs.edit().remove("status").apply() }
        if(setupPrefs.getBoolean("start_measurement",false)) { shellWanted=true; setupPrefs.edit().putBoolean("start_measurement",false).apply() }
        if (shellWanted && probe == null && !launchingHelper) startProbe(shell = true)
    }
    override fun onStop() {
        visible=false; handler.removeCallbacks(ticker); sensors.unregisterListener(this)
        if(probe!=null && testUntil==0L) { stopProbe(); setStatus("Измерение приостановлено вне лаборатории; после возврата помощник подключится снова.") }
        super.onStop()
    }
    override fun onDestroy() { releaseTestWakeLock(); handler.removeCallbacks(auditTick); stopProbe(); scope.cancel(); mdns?.stop(); super.onDestroy() }
    override fun onSensorChanged(event: SensorEvent) {
        if(event.sensor.type==Sensor.TYPE_HINGE_ANGLE) {
            rawAngle=event.values.firstOrNull()?.takeIf { it.isFinite() }
            publicReceived=SystemClock.elapsedRealtime()
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    private fun render() {
        if(::animationStatus.isInitialized) {
            val access=if(EverywhereAnimationService.instance!=null) "Служба включена" else "Нужна служба анимации"
            val notifications=if(getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) "уведомления разрешены" else "нужно разрешить уведомления"
            animationStatus.text="$access · $notifications\n${EverywhereAnimationService.status}\n${state.text}"
        }
        val now=SystemClock.elapsedRealtime()
        val fresh=AngleData.fresh(received,now) && probe!=null
        readinessText.text="Прямой запуск: ${if(hasDirectAccess()) "доступ выдан" else "доступ не выдан"}\nСлужба настройки: ${if(SetupAccessibilityService.instance!=null) "включена" else "выключена (для работающего помощника не нужна)"}\nПараметры разработчика: ${if(Settings.Global.getInt(contentResolver,"development_settings_enabled",0)==1) "включены" else "выключены"}\n"+readiness.summary(fresh)
        audit?.let { auditText.text=(if(testUntil>now) "Проверка: осталось ${(testUntil-now)/1000} с\n" else "Результат проверки\n")+it.report() }
        privateText.text=privateAngle?.let { String.format(Locale.US,"%.3f°",it) } ?: "—"
        privateText.setTextColor(if(fresh) accent else muted)
        publicText.text="Android: "+(rawAngle?.let { String.format(Locale.US,"%.3f°",it) } ?: "—")
        val age=if(received==0L) "нет данных" else "${now-received} мс назад" + if(!fresh) " · УСТАРЕЛО / остановлено" else ""
        val rate=if(samples>1 && now>sampleStart) (samples-1)*1000f/(now-sampleStart) else 0f
        stats.text="Samsung: $age\nСообщений: $samples · разных значений: ${distinct.size} · ${String.format(Locale.US,"%.1f",rate)} сообщений/с\nДиапазон: ${distinct.firstOrNull() ?: "—"} … ${distinct.lastOrNull() ?: "—"}°\nAndroid: $publicName\nПоследнее событие Android: ${if(publicReceived==0L) "нет" else "${now-publicReceived} мс назад (датчик событийный)"}"
        val intermediate = distinct.filter { it != 0f && it != 90f && it != 180f }
        stats.append("\nSamsung — значений кроме 0/90/180: ${intermediate.size}\nПримеры: ${intermediate.take(12).joinToString { String.format(Locale.US, "%.3f°", it) }.ifEmpty { "пока нет" }}")
        gauge.angle=if(fresh) privateAngle else null; gauge.publicAngle=rawAngle; gauge.invalidate()
        events.text=history.joinToString("\n").ifEmpty { "Пока нет показаний Samsung. Если ADB подключён, но данных нет — этот способ ещё не подтверждён на данной прошивке/обоях." }
        diagnosticText.text=diagnostics.joinToString("\n").ifEmpty { "Измерение Samsung ещё не запускалось" } +
            if(probe!=null && received==0L && now-startedAt>8000) "\n\nПоказаний пока нет. Скопируй отчёт: важно проверить ответы команд выше, а не только факт сопряжения." else ""
    }
    private fun addDiagnostic(value: String) {
        diagnostics.addLast(String.format(Locale.US,"%tT · %s",System.currentTimeMillis(),value))
        while(diagnostics.size>30) diagnostics.removeFirst()
    }
    private fun setStatus(value: String) { state.text=value; addDiagnostic(value) }
    private fun message(value: String) { state.text=value; Toast.makeText(this,value,Toast.LENGTH_LONG).show() }
    private fun text(value: String,size: Int,color: Int)=TextView(this).apply { text=value; textSize=size.toFloat(); setTextColor(color); setPadding(0,dp(5),0,dp(5)) }
    private fun label(value: String,size: Int,color: Int=Color.WHITE): TextView = text(value,size,color).also { root.addView(it) }
    private fun button(value: String,prominent:Boolean=false,action:()->Unit) { root.addView(Button(this).apply {
        text=value; textSize=15f; isAllCaps=false; minHeight=dp(54); gravity=Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(16),dp(10),dp(16),dp(10)); setTextColor(Color.WHITE)
        background=background(if(prominent) 0xff087f72.toInt() else 0xff243746.toInt()); setOnClickListener { action() }
    },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6); bottomMargin=dp(3) }) }
    private fun enableEverywhere() {
        if(Build.VERSION.SDK_INT<33) { message("Для анимации нужен Android 13 или новее"); return }
        val service=EverywhereAnimationService.instance
        if(service==null) {
            message("Сначала нажми «3. Включить службу анимации»")
            return
        }
        if(!getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) {
            message("Сначала нажми «4. Разрешить уведомление с остановкой»")
            return
        }
        testUntil=0; releaseTestWakeLock(); handler.removeCallbacks(auditTick)
        setShowWhenLocked(false); stopProbe()
        service.enable(86,boundedTest=false,fallbackExperiment=2)
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
    }
    private fun field(hintText: String): EditText = EditText(this).apply { hint=hintText; textSize=15f; inputType=InputType.TYPE_CLASS_NUMBER; isSingleLine=true; root.addView(this,LinearLayout.LayoutParams(-1,dp(56))) }
    private fun background(color: Int)=GradientDrawable().apply { setColor(color); cornerRadius=dp(20).toFloat() }
    private fun dp(value: Int)=(resources.displayMetrics.density*value).toInt()
    private inner class Gauge(context: Context): View(context) {
        var angle: Float?=null; var publicAngle: Float?=null
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val l=dp(8).toFloat(); val r=width-dp(8).toFloat()
            paint.color=0xff455361.toInt(); paint.strokeWidth=dp(3).toFloat(); canvas.drawLine(l,height*.45f,r,height*.45f,paint)
            paint.textSize=dp(12).toFloat(); paint.color=muted
            canvas.drawText("0°",l,height*.85f,paint); canvas.drawText("90°",width*.5f-dp(10),height*.85f,paint); canvas.drawText("180°",r-dp(30),height*.85f,paint)
            publicAngle?.let { paint.color=0xff91baff.toInt(); canvas.drawCircle(l+(r-l)*it.coerceIn(0f,180f)/180, height*.45f,dp(7).toFloat(),paint) }
            angle?.let { paint.color=accent; canvas.drawCircle(l+(r-l)*it/180,height*.45f,dp(4).toFloat(),paint) }
        }
    }
    companion object { private val accent=0xff80ded0.toInt(); private val muted=0xffa5b2c1.toInt() }
}


