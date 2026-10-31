package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.*
import org.junit.Test
class SurfaceFrameTimelineTest {
    @Test fun queueDelayDoesNotReplaceSourceFrameSpacing(){
        val t=SurfaceFrameTimeline()
        assertEquals(1_000_000L,t.map(900_000_000,1_000_000))
        assertEquals(1_033_333L,t.map(900_033_333,1_300_000))
        assertEquals(1_066_666L,t.map(900_066_666,1_900_000))
    }
    @Test fun duplicateOrRegressedPtsNeverMovesBackwards(){
        val t=SurfaceFrameTimeline()
        assertEquals(500L,t.map(100,500))
        assertEquals(501L,t.map(100,600))
        assertEquals(502L,t.map(90,700))
        assertEquals(550L,t.map(150,800))
    }
    @Test fun replacementCodecStartsAtNewArrivalClock(){
        val first=SurfaceFrameTimeline();val second=SurfaceFrameTimeline()
        assertEquals(1000L,first.map(100000,1000))
        assertEquals(5000L,second.map(0,5000))
    }
}
