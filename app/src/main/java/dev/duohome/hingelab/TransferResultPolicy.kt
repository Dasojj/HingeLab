package dev.duohome.hingelab

/** REJECTED alone is not proof of safety: an attempted move may need restoration. */
object TransferResultPolicy {
    fun safeCompletion(line:String)=line.startsWith("TRANSFER RESTORED ") || line.startsWith("TRANSFER NOT_MOVED ")
    fun failedCompletion(line:String)=line.startsWith("TRANSFER RESTORE_FAILED ")
}
