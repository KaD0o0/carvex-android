package io.github.iamr0s.carvex.carplay.core

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class AudioRtpTimestampClockTest {
    @Test fun mainAndAlternateNeedAnExplicitBooleanTrueReplyForLegacySourcePts() {
        val replies=listOf<Map<*,*>>(
            emptyMap<String,Any>(),
            mapOf("supportsHighAccuracyTimestamps" to false),
            mapOf("supportsHighAccuracyTimestamps" to null),
            mapOf("supportsHighAccuracyTimestamps" to "true"),
            mapOf("supportsHighAccuracyTimestamps" to 1),
            mapOf("supportsHighAccuracyTimestamps" to true)
        )
        for(type in listOf(100,101))for((index,reply) in replies.withIndex()){
            val enabled=AudioRtpTimestampClock.receiverSupportsHighAccuracy(reply)
            assertEquals(index==replies.lastIndex,enabled)
            val clock=AudioRtpTimestampClock(type,enabled,17)
            assertEquals(if(enabled)AudioRtpTimestampClock.Mode.LEGACY_SOURCE_PTS
                else AudioRtpTimestampClock.Mode.CONTINUOUS_SAMPLES,clock.mode)
            assertEquals(if(enabled)48017L else 17L,clock.timestamp(48000))
        }
    }

    @Test fun highQualityMediaKeepsItsExistingSourcePtsPolicyRegardlessOfReply() {
        for(enabled in listOf(false,true)){
            val clock=AudioRtpTimestampClock(102,enabled,9)
            assertEquals(AudioRtpTimestampClock.Mode.LEGACY_SOURCE_PTS,clock.mode)
            assertEquals(1033L,clock.timestamp(1024))
            clock.sent(1024)
            assertEquals(144009L,clock.timestamp(144000))
            // Committing a send cannot move the independent source-PTS coordinate.
            assertEquals(1033L,clock.timestamp(1024))
        }
    }

    @Test fun discardedOrSuppressedCandidatesDoNotConsumeContinuousSamples() {
        val clock=AudioRtpTimestampClock(101,false,123)
        // These candidates represent rejected ownership, stale AUs, and suppressed
        // output. None has completed a primary UDP send, so there is no commit.
        for(source in listOf(0L,320L,16000L,48000L))assertEquals(123L,clock.timestamp(source))
        clock.sent(320)
        assertEquals(443L,clock.timestamp(48320))
        assertEquals(443L,clock.timestamp(80000))
        clock.sent(320)
        assertEquals(763L,clock.timestamp(80320))
        // Re-sending cached bytes likewise does not call sent a second time.
        repeat(3){assertEquals(763L,clock.timestamp(80320))}
    }

    @Test fun threeSecondSourcePauseChangesPacingButNotTheNextWireSamplePosition() {
        for(type in listOf(100,101))for(rate in listOf(16000,24000,48000)){
            val samples=rate/50
            val source=AudioSourceClock(rate)
            val sender=AudioSendClock(rate)
            val wire=AudioRtpTimestampClock(type,false,37)
            source.reserve(samples,1_000_000_000L)
            val first=sender.plan(0,source.sourceTimeNanos(0),1_010_000_000L,samples)
            assertEquals(37L,wire.timestamp(first.timestamp))
            wire.sent(samples)

            val resumed=source.reserve(samples,4_000_000_000L)
            val pts=source.ptsUs(resumed.firstFrame)
            val next=sender.plan(pts,source.sourceTimeNanos(pts),4_010_000_000L,samples)
            assertEquals(4_010_000_000L,next.dueNanos)
            assertEquals(rate*3L,next.timestamp)
            assertTrue(next.discontinuity) // The writer must still discard RED history.
            assertFalse(sender.expired(next,4_010_000_000L))
            assertEquals(37L+samples,wire.timestamp(next.timestamp))
        }
    }

    @Test fun negativePrimingAndAStaleAuLeavePacingDeadlinesIntactAndWireSamplesDense() {
        val sender=AudioSendClock(48000)
        val wire=AudioRtpTimestampClock(100,false)
        val priming=sender.plan(-21_333,978_667_000L,1_060_000_000L,1024)
        assertEquals(0L,priming.timestamp)
        assertEquals(1_060_000_000L,priming.dueNanos)
        assertFalse(sender.expired(priming,1_060_000_000L))
        assertEquals(0L,wire.timestamp(priming.timestamp))
        wire.sent(1024)

        val stale=sender.plan(0,1_000_000_000L,1_061_000_000L,1024)
        assertTrue(sender.expired(stale,1_250_000_000L))
        assertEquals(1024L,wire.timestamp(stale.timestamp)) // Discard: no commit.
        val fresh=sender.plan(192_000,1_192_000_000L,1_260_000_000L,1024)
        assertEquals(1_273_333_000L,fresh.dueNanos)
        assertFalse(sender.expired(fresh,1_260_000_000L))
        assertTrue(fresh.discontinuity)
        assertEquals(10240L,fresh.timestamp)
        assertEquals(1024L,wire.timestamp(fresh.timestamp))
        wire.sent(1024)
        assertEquals(2048L,wire.timestamp(fresh.timestamp+1024))
        assertEquals(81_333_000L,sender.startupDelayNanos)
    }

    @Test fun staleFirstAuDoesNotChooseTheFirstActuallySentTimestamp() {
        val sender=AudioSendClock(48000)
        val wire=AudioRtpTimestampClock(101,false,91)
        val stale=sender.plan(0,0,30_000_000L,960)
        assertTrue(sender.expired(stale,200_000_000L))
        assertEquals(91L,wire.timestamp(stale.timestamp))
        val fresh=sender.plan(200_000,200_000_000L,220_000_000L,960)
        assertFalse(sender.expired(fresh,230_000_000L))
        assertEquals(230_000_000L,fresh.dueNanos)
        assertEquals(9600L,fresh.timestamp)
        assertEquals(91L,wire.timestamp(fresh.timestamp))
        wire.sent(960)
        assertEquals(1051L,wire.timestamp(10560))
    }

    @Test fun opusAndAacAdvanceByActualPacketSamplesWithoutRateRoundingDrift() {
        val formats=listOf(16000 to 320,24000 to 480,48000 to 960,44100 to 1024,48000 to 1024)
        for((rate,samples) in formats){
            val sender=AudioSendClock(rate)
            val wire=AudioRtpTimestampClock(100,false,5)
            repeat(10000){index->
                val sourceFrame=index*samples.toLong()
                val pts=sourceFrame*1_000_000L/rate
                val plan=sender.plan(pts,pts*1000,pts*1000+10_000_000L,samples)
                assertEquals(sourceFrame,plan.timestamp)
                assertEquals(5L+sourceFrame,wire.timestamp(plan.timestamp))
                wire.sent(samples)
            }
            assertEquals(5L+10000L*samples,wire.timestamp(0))
        }
    }

    @Test fun extendedTimestampWrapsOnlyAtSerializationAndKeepsTheCorrectRedAge() {
        val clock=AudioRtpTimestampClock(101,false,0xffffff00L)
        val previous=clock.timestamp(0)
        clock.sent(960)
        val timestamp=clock.timestamp(144000)
        assertEquals(0x1000002c0L,timestamp)
        val packet=MediaAudioPackets.redPacket(byteArrayOf(2),byteArrayOf(1),previous,
            ByteArray(32),0,timestamp,1,101)
        assertEquals(0x2c0L,ByteBuffer.wrap(packet).getInt(4).toLong() and 0xffffffffL)
        assertEquals(0x80 or 101,packet[12].toInt() and 255)
        assertEquals(960,((packet[13].toInt() and 255) shl 6) or
            ((packet[14].toInt() and 255) ushr 2))
        assertArrayEquals(byteArrayOf(1,2),Crypto.open(ByteArray(32),Crypto.nonce(1),
            packet.copyOfRange(17,packet.size-8),packet.copyOfRange(4,12)))
    }

    @Test fun aFullWrapDoesNotMakeAncientRedHistoryAppearCurrent() {
        val clock=AudioRtpTimestampClock(100,false)
        val previous=clock.timestamp(0)
        clock.sent(Int.MAX_VALUE)
        clock.sent(Int.MAX_VALUE)
        clock.sent(2)
        val timestamp=clock.timestamp(0)
        assertEquals(0x100000000L,timestamp)
        val packet=MediaAudioPackets.redPacket(byteArrayOf(2),byteArrayOf(1),previous,
            ByteArray(32),1,timestamp,1,100)
        assertEquals(0L,ByteBuffer.wrap(packet).getInt(4).toLong() and 0xffffffffL)
        assertEquals(100,packet[12].toInt() and 255) // Primary-only RED, not zero-age history.
    }

    @Test fun newTransportClockDoesNotInheritPreviousSamplesOrPolicy() {
        val old=AudioRtpTimestampClock(100,false,10)
        old.sent(320)
        assertEquals(330L,old.timestamp(48000))
        val next=AudioRtpTimestampClock(100,false,20)
        assertEquals(20L,next.timestamp(48000))
        val highAccuracyReply=AudioRtpTimestampClock(100,true,30)
        assertEquals(48030L,highAccuracyReply.timestamp(48000))
        assertEquals(330L,old.timestamp(48000))
        assertEquals(AudioRtpTimestampClock.Mode.CONTINUOUS_SAMPLES,old.mode)
    }
}
