package dev.duohome.hingelab

import org.junit.Assert.*
import org.junit.Test

class NativePanelFramesTest {
    private val inner=PanelSize(2448,1848,0)
    private val cover=PanelSize(1248,1972,0)
    @Test fun innerIsNeverUsedAsCover() {
        val frames=NativePanelFrames<String>(); frames.put(inner,"inner",100,1)
        assertNull(frames.get(cover,101,1)); assertEquals("inner",frames.get(inner,101,1))
    }
    @Test fun coverSurvivesLogicalDisplayIdSwapButNotAppChangeOrExpiry() {
        val frames=NativePanelFrames<String>(); frames.put(cover,"cover",100,1)
        frames.put(inner,"inner",150,1)
        assertEquals("cover",frames.get(cover,200,1))
        assertNull(frames.get(cover,200,2)); assertNull(frames.get(cover,10101,1))
        assertNull(frames.get(cover.copy(rotation=1),200,1))
    }
    @Test fun cacheDoesNotAccumulateRotations() {
        val frames=NativePanelFrames<String>(); frames.put(inner,"a",100,1)
        frames.put(cover,"b",100,1); frames.put(inner.copy(rotation=1),"c",100,1)
        assertNull(frames.get(inner,101,1))
        frames.clear(); assertNull(frames.get(cover,101,1))
    }
    @Test fun rejectedInnerFrameDoesNotDiscardNativeCover() {
        val frames=NativePanelFrames<String>()
        frames.put(inner,"inner",100,1); frames.put(cover,"cover",100,1)
        frames.remove(inner)
        assertNull(frames.get(inner,200,1))
        assertEquals("cover",frames.get(cover,200,1))
    }
    @Test fun blackAndTransparentFramesFailClosed() {
        assertFalse(NativeFrameQuality.usable(IntArray(100){0xff000000.toInt()}))
        assertFalse(NativeFrameQuality.usable(IntArray(100){0x00ffffff}))
        assertTrue(NativeFrameQuality.usable(IntArray(100){0xff808080.toInt()}))
    }
    @Test fun pendingAttachWindowIsStillRemoved() {
        var removalCalls=0
        val windows=OwnedOverlays<Int,String> { removalCalls++ }
        assertTrue(windows.add(0,"not yet attached"))
        assertFalse(windows.add(0,"duplicate"))
        windows.clear(); assertEquals(1,removalCalls); assertTrue(windows.isEmpty())
    }
    @Test fun removeFailureDoesNotLoseWindowOwnership() {
        var fail=true
        val windows=OwnedOverlays<Int,String> { if(fail) error("simulated failure") }
        windows.add(0,"owned")
        runCatching { windows.drop(0) }
        assertEquals("owned",windows.get(0))
        fail=false; windows.drop(0); assertTrue(windows.isEmpty())
    }
}
