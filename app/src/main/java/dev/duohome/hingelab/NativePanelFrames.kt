package dev.duohome.hingelab

data class PanelSize(val width:Int,val height:Int,val rotation:Int)

/** Native-size frames only. Logical display IDs are deliberately not cache keys. */
class NativePanelFrames<T>(private val ttl:Long=10000) {
    private data class Entry<T>(val value:T,val at:Long,val epoch:Int)
    private val entries=linkedMapOf<PanelSize,Entry<T>>()
    fun put(size:PanelSize,value:T,now:Long,epoch:Int) {
        entries[size]=Entry(value,now,epoch)
        while(entries.size>2) entries.remove(entries.keys.first())
    }
    fun get(size:PanelSize,now:Long,epoch:Int):T? = entries[size]?.takeIf {
        it.epoch==epoch && now-it.at in 0..ttl
    }?.value
    fun expire(now:Long) { entries.entries.removeAll { now-it.value.at !in 0..ttl } }
    fun remove(size:PanelSize) { entries.remove(size) }
    fun clear()=entries.clear()
}

object NativeFrameQuality {
    // A nearly black/transparent capture may be a secure surface or a transition.
    // Skipping a legitimately black app is preferable to covering it with a bad frame.
    fun usable(pixels:IntArray):Boolean = pixels.isNotEmpty() && pixels.count {
        (it ushr 24)>=240 && maxOf((it ushr 16) and 255,(it ushr 8) and 255,it and 255)>12
    } * 100 >= pixels.size * 5
}

/** Own from addView, not from onAttachedToWindow. Always request actual removal. */
class OwnedOverlays<K,V>(private val remove:(V)->Unit) {
    private val values=linkedMapOf<K,V>()
    fun get(key:K)=values[key]
    fun entries()=values.toMap()
    fun add(key:K,value:V):Boolean {
        if(values.containsKey(key)) return false
        values[key]=value; return true
    }
    fun drop(key:K) {
        val value=values[key] ?: return
        remove(value) // Failure retains ownership for fail-closed handling.
        values.remove(key)
    }
    fun clear() { values.keys.toList().forEach(::drop) }
    fun isEmpty()=values.isEmpty()
}
