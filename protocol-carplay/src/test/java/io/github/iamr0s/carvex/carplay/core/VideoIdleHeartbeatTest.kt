package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.*
import org.junit.Test
class VideoIdleHeartbeatTest {
    @Test fun nothingIsRepeatedBeforeTheFirstFrame(){
        val h=VideoIdleHeartbeat(1000)
        // A repeat only repeats what the codec already produced; it cannot start the stream.
        assertFalse(h.beat(0))
        assertFalse(h.beat(60_000))
    }
    @Test fun stillPictureRepeatsOncePerInterval(){
        val h=VideoIdleHeartbeat(1000)
        h.frame(0)
        assertFalse(h.beat(999))
        assertTrue(h.beat(1000))
        assertFalse(h.beat(1500))
        assertTrue(h.beat(2000))
        assertFalse(h.beat(2500))
        assertTrue(h.beat(3000))
    }
    @Test fun theRepeatItselfDoesNotStretchTheCadence(){
        // The still picture asks for a repeat every interval, and the codec answers a few
        // milliseconds later. That answer is the repeat coming back, not the picture moving, so it
        // must not push the next repeat a whole interval further out.
        val h=VideoIdleHeartbeat(1000)
        h.frame(0)
        assertTrue(h.beat(1000))
        h.frame(1060)
        assertTrue(h.beat(2000))
        h.frame(2075)
        assertTrue(h.beat(3000))
    }
    @Test fun aStillPictureProducesExactlyOneRepeatPerTickForAsLongAsItStaysStill(){
        val h=VideoIdleHeartbeat(1000)
        h.frame(0)
        for(tick in 1..10){
            val now=tick*1000L
            assertTrue("tick $tick must repeat",h.beat(now))
            // Encoder latency: the repeat comes back a few milliseconds after the request.
            h.frame(now+40)
        }
    }
    @Test fun missingRepeatIsRetriedOnTheNextIntervalOnly(){
        val h=VideoIdleHeartbeat(1000)
        h.frame(0)
        assertTrue(h.beat(1000))
        // The repeat never produced a frame: retry, but no faster than the interval.
        assertFalse(h.beat(1500))
        assertTrue(h.beat(2000))
    }
    @Test fun movingPictureStopsRepeating(){
        val h=VideoIdleHeartbeat(1000)
        h.frame(0)
        assertTrue(h.beat(1000))
        // The picture keeps producing frames, so nothing is due a second later.
        for(i in 1..30)h.frame(1000+i*33L)
        assertFalse(h.beat(2000))
        assertFalse(h.beat(2989))
    }
    @Test fun intervalMustBePositive(){
        assertThrows(IllegalArgumentException::class.java){VideoIdleHeartbeat(0)}
    }
}
