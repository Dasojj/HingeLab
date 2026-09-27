package dev.duohome.hingelab

/** Short, user-initiated lab session; counts movement separately in each context. */
class ContextAudit {
    private data class Row(var ticks: Int=0, var heartbeats: Int=0, var samples: Int=0, val values: MutableSet<Float> = sortedSetOf())
    private val rows=linkedMapOf<String,Row>()
    fun observe(context: String, angle: Float?=null) {
        val row=rows.getOrPut(context) { Row() }; row.ticks++
        if(angle!=null) { row.samples++; row.values.add(angle) }
    }
    fun heartbeat(context: String) { rows.getOrPut(context) { Row() }.heartbeats++ }
    fun report() = rows.entries.joinToString("\n") { (name,row) ->
        val changing=row.values.size>=3 && ((row.values.maxOrNull() ?: 0f)-(row.values.minOrNull() ?: 0f))>=5f
        "$name: связь ${row.heartbeats} сигналов, ${row.samples} ответов, ${row.values.size} значений · ${if(changing) "изменение замечено" else "изменение НЕ подтверждено"}"
    }.ifEmpty { "Сценарии ещё не проверялись" } + "\nСигналы связи подтверждают работу помощника, но не датчика. Постоянное значение не доказывает работоспособность. Во время каждого сценария нужно двигать шарнир. Это короткая проверка, не тест автономности."
}
