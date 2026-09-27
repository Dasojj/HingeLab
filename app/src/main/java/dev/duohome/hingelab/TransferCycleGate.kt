package dev.duohome.hingelab
/** Another move needs both restoration and an endpoint, in either order. */
class TransferCycleGate {
    private var used=false
    private var busy=false
    private var endpoint=false
    fun begin():Boolean {
        if(used || busy) return false
        used=true; busy=true; endpoint=false; return true
    }
    fun restored() { busy=false; rearm() }
    fun boundary() { endpoint=true; rearm() }
    private fun rearm() { if(!busy && endpoint) used=false }
}
