package dev.duohome.hingelab

/** A live connection and repeated cached values must not count as verified motion. */
class Readiness {
    var direct="не проверен"
    var shellRequests=false
    var connected=false
    var session=false
    var wallpaper="unknown"
    var wallpaperVisible="unknown"
    private val values=sortedSetOf<Float>()
    fun health(value: String) {
        when {
            value.startsWith("direct=") -> { if(direct!="events") direct=value.substringAfter("direct=") }
            value=="source=shell" -> shellRequests=true
            value=="connected" -> connected=true
            value=="session" -> session=true
            value.startsWith("META wallpaper=") -> wallpaper=value.substringAfter('=')
            value.startsWith("visible=") -> wallpaperVisible=value.substringAfter('=')
        }
    }
    fun sample(angle: Float) { if(angle.isFinite() && angle in 0f..180f) values.add(angle) }
    val motionVerified: Boolean get() = values.count { it!=0f && it!=90f && it!=180f }>=3 &&
        (values.lastOrNull() ?: 0f)-(values.firstOrNull() ?: 0f)>=5f
    fun summary(fresh: Boolean): String = listOf(
        "Запросы: ${if(shellRequests) "из shell, без окна Lab" else "не подтверждены — обнови запуск помощника"}",
        "Прямой датчик: ${when(direct) { "events" -> "реальные события получены"; "subscribed" -> "подписка принята, ждём события"; else -> direct }}",
        "Помощник: ${if(connected) "соединение подтверждено" else "не подключён"}",
        "FoldInteractive: ${when(wallpaper) { "present" -> "компонент найден"; "absent" -> "не найден среди текущих обоев"; else -> "ещё не проверен" }}",
        "Обои активны: ${when(wallpaperVisible) { "true" -> "да"; "false" -> "нет — угол может быть сохранённым"; else -> "неизвестно" }}",
        "Поток: ${if(fresh && session) "свежие ответы этого сеанса" else if(fresh) "ответы есть; перезапусти помощник для новой проверки" else "свежих ответов нет"}",
        "Изменение угла: ${if(motionVerified) "промежуточные значения подтверждены" else "медленно согни и разогни телефон на 20–30°"}",
        "Это проверка текущей прошивки и обоев, не гарантия для других Fold."
    ).joinToString("\n")
}
