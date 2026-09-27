package dev.duohome.hingelab

object ForegroundVerification {
    // 0: keep verified owner; 1: accept event; 2: clear ambiguous imagery.
    fun decide(actual:String?,candidate:String,previous:String):Int = when {
        actual==null -> 2
        actual==candidate -> 1
        actual==previous -> 0
        else -> 2
    }
}
