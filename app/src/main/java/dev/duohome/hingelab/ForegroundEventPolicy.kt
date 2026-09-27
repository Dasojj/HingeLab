package dev.duohome.hingelab

object ForegroundEventPolicy {
    // TYPE_WINDOW_STATE_CHANGED also describes popups and secondary windows.
    // Only a primary full-screen window establishes the foreground frame owner.
    fun establishesOwner(displayId:Int,fullScreen:Boolean)=displayId==0 && fullScreen
}
