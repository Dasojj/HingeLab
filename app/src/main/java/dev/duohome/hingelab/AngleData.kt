package dev.duohome.hingelab

object AngleData {
    private const val number = "[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][-+]?\\d+)?"
    private val action = Regex("\\bonCommand:\\s*action(?:\\[hingelab_angle(?:_[a-f0-9]+)?\\]|\\s*=\\s*hingelab_angle(?:_[a-f0-9]+)?(?=\\s*(?:,|$)))")
    private val value = Regex("\\bmCurrentAngle(?:\\[($number)\\]|\\s*=\\s*($number)(?=\\s*(?:,|$)))")
    fun parse(line: String): Float? {
        if (!action.containsMatchIn(line)) return null
        val match = value.find(line) ?: return null
        return (match.groups[1]?.value ?: match.groups[2]?.value)?.toFloatOrNull()
            ?.takeIf { it.isFinite() && it in 0f..180f }
    }
    fun fresh(received: Long, now: Long) = received > 0 && now - received in 0..1500
}
