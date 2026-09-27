package dev.duohome.hingelab

object ZFoldLog {
    private val stopped=Regex("unregisterSensor:\\s*mIsSensorRegistered(?:\\[true\\]|\\s*=\\s*true)")
    fun unsubscribed(line: String)=line.contains("FoldInteractive") && stopped.containsMatchIn(line)
}
