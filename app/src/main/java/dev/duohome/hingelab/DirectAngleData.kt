package dev.duohome.hingelab
object DirectAngleData {
    fun parse(line: String): Float? {
        if(!line.startsWith("DIRECT_ANGLE ")) return null
        return line.removePrefix("DIRECT_ANGLE ").toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..180f }
    }
}
