package io.github.iamr0s.carvex.carplay.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class AudioPipelineTimingTest {
    @Test fun inputClockUsesSamplesNotEncoderScheduling() {
        val clock=AudioSourceClock(48000)
        val first=clock.reserve(1920,1_000_000_000)
        val next=clock.reserve(1920,1_040_000_000)
        assertEquals(0L,first.firstFrame)
        assertEquals(1920L,next.firstFrame)
        assertEquals(0L,next.gapFrames)
        // Processing either block much later cannot change its reservation.
        assertEquals(40_000L,clock.ptsUs(next.firstFrame))
        assertEquals(1_040_000_000L,clock.sourceTimeNanos(clock.ptsUs(next.firstFrame)))
    }

    @Test fun sourcePauseAdvancesSamplesButVariableBlockSizeDoesNotInventAGap() {
        val clock=AudioSourceClock(48000)
        clock.reserve(1920,1_000_000_000)
        val variable=clock.reserve(3840,1_080_000_000)
        assertEquals(0L,variable.gapFrames)
        val resumed=clock.reserve(1920,1_680_000_000)
        assertEquals(26880L,resumed.gapFrames) // 600 ms between callbacks minus this 40 ms block.
        assertEquals(32640L,resumed.firstFrame)
        assertEquals(680_000L,clock.ptsUs(resumed.firstFrame))
    }

    @Test fun rejectedOrExpiredPcmStillOccupiesItsSampleTime() {
        val clock=AudioSourceClock(48000)
        clock.reserve(1920,0)
        clock.reserve(1920,40_000_000) // Reserved, then rejected by the bounded PCM queue.
        val accepted=clock.reserve(1920,80_000_000)
        assertEquals(3840L,accepted.firstFrame)
        assertEquals(80_000L,clock.ptsUs(accepted.firstFrame))
    }

    @Test fun repeatedShortSourcePausesCannotLeaveEveryFutureAuExpired() {
        val clock=AudioSourceClock(48000)
        clock.reserve(1920,0)
        val firstPause=clock.reserve(1920,120_000_000) // 40 ms audio plus an 80 ms pause.
        assertEquals(0L,firstPause.gapFrames)
        val secondPause=clock.reserve(1920,240_000_000)
        assertEquals(7680L,secondPause.gapFrames) // Account for the cumulative 160 ms missing time.
        assertEquals(240_000L,clock.ptsUs(secondPause.firstFrame))
        val regular=clock.reserve(1920,280_000_000)
        assertEquals(0L,regular.gapFrames)
        assertEquals(280_000L,clock.ptsUs(regular.firstFrame))
        val sender=AudioSendClock(48000)
        sender.plan(0,0,20_000_000,1024)
        val pts=clock.ptsUs(regular.firstFrame)
        val plan=sender.plan(pts,clock.sourceTimeNanos(pts),300_000_000,1024)
        assertFalse(sender.expired(plan,300_000_000))
    }

    @Test fun missingUpstreamBlocksAreAccountedForOnce() {
        val clock=AudioSourceClock(48000)
        clock.reserve(1920,0)
        // The host bridge discarded three 40 ms blocks before they reached this boundary.
        val current=clock.reserve(1920,160_000_000)
        assertEquals(5760L,current.gapFrames)
        assertEquals(160_000L,clock.ptsUs(current.firstFrame))
        val next=clock.reserve(1920,200_000_000)
        assertEquals(0L,next.gapFrames)
        assertEquals(200_000L,clock.ptsUs(next.firstFrame))
    }

    @Test fun boundedCallbackJitterDoesNotAccumulateIntoFalseSourceGaps() {
        val clock=AudioSourceClock(48000)
        val jitter=longArrayOf(0,10,30,5,20,0)
        repeat(1000){index->
            val arrival=index*40_000_000L+jitter[index%jitter.size]*1_000_000L
            val reserved=clock.reserve(1920,arrival)
            assertEquals(index*1920L,reserved.firstFrame)
            assertEquals(0L,reserved.gapFrames)
        }
    }

    @Test fun negativeCodecPrimingPtsAndNormalStartupDelayAreNotStale() {
        val clock=AudioSendClock(48000)
        val first=clock.plan(-21_333,978_667_000,1_060_000_000,1024)
        assertEquals(1_060_000_000L,first.dueNanos)
        assertEquals(0L,first.timestamp)
        assertFalse(clock.expired(first,1_060_000_000))
        val next=clock.plan(0,1_000_000_000,1_061_000_000,1024)
        assertEquals(1_081_333_000L,next.dueNanos)
        assertEquals(1024L,next.timestamp)
        assertFalse(next.discontinuity)
    }

    @Test fun stalledWriterDropsOldAuWithoutMovingEveryFutureDeadline() {
        val clock=AudioSendClock(48000)
        clock.plan(0,0,20_000_000,1024)
        val old=clock.plan(21_333,21_333_000,40_000_000,1024)
        assertTrue(clock.expired(old,200_000_000))
        val fresh=clock.plan(192_000,192_000_000,205_000_000,1024)
        assertEquals(212_000_000L,fresh.dueNanos)
        assertEquals(9216L,fresh.timestamp) // Skipped AUs did not compress the source/pacing timeline.
        assertTrue(fresh.discontinuity)
        assertFalse(clock.expired(fresh,205_000_000))
        assertEquals(20_000_000L,clock.startupDelayNanos)
    }

    @Test fun firstAuOutputQueueDelayIsNotTreatedAsCodecStartup() {
        val clock=AudioSendClock(48000)
        val first=clock.plan(0,0,30_000_000,1024)
        assertTrue(clock.expired(first,200_000_000))
        assertEquals(30_000_000L,clock.startupDelayNanos)
    }

    @Test fun pauseResumesAtCurrentTimeAndMarksRedHistoryDiscontinuity() {
        val source=AudioSourceClock(48000)
        val sender=AudioSendClock(48000)
        source.reserve(960,1_000_000_000)
        val first=sender.plan(0,source.sourceTimeNanos(0),1_010_000_000,960)
        assertFalse(first.discontinuity)
        val resumed=source.reserve(960,2_000_000_000)
        val pts=source.ptsUs(resumed.firstFrame)
        val plan=sender.plan(pts,source.sourceTimeNanos(pts),2_010_000_000,960)
        assertEquals(2_010_000_000L,plan.dueNanos)
        assertEquals(48000L,plan.timestamp)
        assertTrue(plan.discontinuity)
        assertFalse(sender.expired(plan,2_010_000_000))
    }

    @Test fun timestampRoundingDoesNotLoseSamplesAt44100Or48000Hz() {
        for(rate in listOf(44100,48000)){
            val source=AudioSourceClock(rate)
            val sender=AudioSendClock(rate)
            source.reserve(1024,0)
            repeat(10000){index->
                val pts=source.ptsUs(index*1024L)
                val plan=sender.plan(pts,pts*1000,pts*1000+10_000_000,1024)
                assertEquals(index*1024L,plan.timestamp)
                assertFalse(plan.discontinuity)
            }
        }
    }

    @Test fun continuousCodecOutputPtsRecoverTheSourcePauseHole() {
        val timeline=AudioSampleTimeline(48000)
        assertEquals(0L,timeline.record(0,960))
        assertEquals(20_000L,timeline.record(48000,960)) // Codec never receives a jumping PTS.
        assertEquals(0L,timeline.sourceFrame(0))
        assertEquals(48000L,timeline.sourceFrame(20_000))
        assertEquals(1,timeline.retainedSegments)
        val sender=AudioSendClock(48000)
        sender.plan(0,1_000_000_000,1_010_000_000,960)
        val restoredPts=timeline.ptsUs(timeline.sourceFrame(20_000))
        val resumed=sender.plan(restoredPts,1_000_000_000+restoredPts*1000,2_010_000_000,960)
        assertEquals(48000L,resumed.timestamp)
        assertEquals(2_010_000_000L,resumed.dueNanos)
        assertTrue(resumed.discontinuity)
        assertFalse(sender.expired(resumed,2_010_000_000))
    }

    @Test fun firstAcceptedPcmNeedNotStartAtSourceZeroAndPrimingCanBeNegative() {
        val timeline=AudioSampleTimeline(48000)
        assertEquals(0L,timeline.record(9600,1920))
        assertEquals(8576L,timeline.sourceFrame(-21_333))
        assertEquals(9600L,timeline.sourceFrame(0))
        assertEquals(10624L,timeline.sourceFrame(21_333))
    }

    @Test fun sourceBoundaryInsideAnAccessUnitIsAppliedAtTheNextAuStart() {
        val timeline=AudioSampleTimeline(48000)
        timeline.record(0,1920)
        timeline.record(9600,1920)
        assertEquals(1024L,timeline.sourceFrame(21_333)) // AU starts before the gap.
        assertEquals(9728L,timeline.sourceFrame(42_666)) // Next AU starts 128 samples after it.
        assertEquals(10752L,timeline.sourceFrame(64_000))
        assertEquals(1,timeline.retainedSegments)
    }

    @Test fun discardedInputRangeDoesNotAppearInDenseCodecClock() {
        val timeline=AudioSampleTimeline(48000)
        timeline.record(0,1024)
        // Source frames [1024,3072) were discarded before feeding MediaCodec.
        assertEquals(21_333L,timeline.record(3072,1024))
        assertEquals(0L,timeline.sourceFrame(0))
        assertEquals(3072L,timeline.sourceFrame(21_333))
    }

    @Test fun longDenseCodecTimelineKeepsOneSegmentAndExactSamplePositions() {
        for(rate in listOf(44100,48000)){
            val timeline=AudioSampleTimeline(rate)
            repeat(10000){index->
                val pts=timeline.record(index*1024L,1024)
                assertEquals(index*1024L,timeline.sourceFrame(pts))
                assertEquals(1,timeline.retainedSegments)
            }
        }
    }

    @Test fun boundedOutputSuspendsAfterReleaseAndPreservesConfigurationOrder()=runBlocking {
        val output=Channel<String>(1)
        output.send("configuration")
        val released=CompletableDeferred<Unit>()
        val producer=launch(start=CoroutineStart.UNDISPATCHED){
            copyReleaseAndPublish(copy={"access-unit"},release={released.complete(Unit)},publish={output.send(it)})
        }
        assertTrue(released.isCompleted)
        assertFalse(producer.isCompleted)
        assertEquals("configuration",output.receive())
        assertEquals("access-unit",output.receive())
        withTimeout(1000){producer.join()}
        output.cancel()
    }

    @Test fun closeCancelsBlockedOutputWithoutHoldingACodecBuffer()=runBlocking {
        val output=Channel<String>(1)
        output.send("configuration")
        var released=false
        val producer=launch(start=CoroutineStart.UNDISPATCHED){
            copyReleaseAndPublish(copy={"access-unit"},release={released=true},publish={output.send(it)})
        }
        assertTrue(released)
        assertFalse(producer.isCompleted)
        output.cancel()
        withTimeout(1000){producer.join()}
        assertTrue(producer.isCancelled)
    }

    @Test fun copyingFailureStillReleasesTheCodecBuffer()=runBlocking {
        var released=false
        try{
            copyReleaseAndPublish<String>(copy={error("copy failed")},release={released=true},publish={fail("must not publish")})
            fail("must fail")
        }catch(_:IllegalStateException){assertTrue(released)}
    }
}
