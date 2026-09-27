package dev.duohome.hingelab
object FallbackPolicy {
    fun allowed(mode:Int,nativeCover:Boolean,closing:Boolean,ready:Boolean,display:Int)=
        mode in 1..2 && !nativeCover && closing && ready && display==1
}
