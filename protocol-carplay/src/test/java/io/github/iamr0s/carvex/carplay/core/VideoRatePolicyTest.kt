package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.*
import org.junit.Test
class VideoRatePolicyTest {
    @Test fun persistentCongestionReducesButSingleStallDoesNot(){
        val p=VideoRatePolicy(6_000_000)
        assertNull(p.sample(0,200,100))
        assertNull(p.sample(1000,200,100))
        assertNull(p.sample(2000,200,100))
        assertEquals(4_800_000,p.sample(3000,200,100))
        assertNull(p.sample(4000,0,5))
    }
    @Test fun recoveryIsSlowAndNeverExceedsConfiguredCeiling(){
        val p=VideoRatePolicy(1_000_000)
        for(i in 0..25)assertNull(p.sample(i*1000L,200,100))
        p.configure(2_000_000)
        for(i in 0..3)p.sample(i*1000L,200,100)
        var recovered:Int?=null
        for(i in 4..18)p.sample(i*1000L,0,1)?.let{recovered=it}
        assertEquals(1_850_000,recovered)
        for(i in 19..33)p.sample(i*1000L,0,1)?.let{recovered=it}
        assertEquals(2_000_000,recovered)
        for(i in 34..65)assertNull(p.sample(i*1000L,0,1))
    }
    @Test fun codecBoundsLimitManualAndAutomaticTargets(){
        val p=VideoRatePolicy(6_000_000,2_000_000,4_000_000)
        assertEquals(4_000_000,p.configure(12_000_000))
        assertEquals(2_000_000,p.configure(1_000_000))
        for(i in 0..30)assertNull(p.sample(i*1000L,200,100))
        assertThrows(IllegalArgumentException::class.java){VideoRatePolicy(1_000_000,3_000_000,2_000_000)}
    }
    @Test fun expandedCodecRangesDoNotOverflowDuringAdaptation(){
        val p=VideoRatePolicy(Int.MAX_VALUE,1,Int.MAX_VALUE)
        for(i in 0..2)assertNull(p.sample(i*1000L,200,100))
        assertEquals((Int.MAX_VALUE.toLong()*4/5).toInt(),p.sample(3000,200,100))
        assertEquals(Int.MAX_VALUE,p.configure(Int.MAX_VALUE))
        for(i in 0..20)assertNull(p.sample(i*1000L,0,1))
    }
}
