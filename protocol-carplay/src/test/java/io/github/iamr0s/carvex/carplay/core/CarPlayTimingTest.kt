package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class CarPlayTimingTest {
    private fun hex(s:String)=s.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    @Test fun responseMatchesPublishedE17MonotonicVector(){
        val request=ByteArray(32).also{it[0]=0x80.toByte();it[1]=0xd2.toByte();hex("0011223344556677").copyInto(it,24)}
        assertEquals(0x83aa7e8180000000UL.toLong(),CarPlayTiming.ntp(1500000000))
        assertArrayEquals(hex("80d3000783aa7e81001122334455667783aa7e814000000083aa7e8180000000"),
            CarPlayTiming.reply(request,1250000000,1500000000))
        assertNull(CarPlayTiming.reply(request.copyOf(48),0,0))
        assertNull(CarPlayTiming.reply(request.copyOf(31),0,0))
        request[0]=0x40;assertNull(CarPlayTiming.reply(request,0,0))
    }
    @Test fun standardNtpUsesWallTimeAndEchoesTheClientTransmitTimestamp(){
        val request=ByteArray(48).also{it[0]=0x23;hex("0011223344556677").copyInto(it,40)}
        val clock=CarPlayTiming.WallClock(anchorUnixMillis=500,anchorMonotonicNs=1_000_000_000)
        val reply=CarPlayTiming.reply(request,1_250_000_000,1_500_000_000,clock)!!
        assertEquals(48,reply.size)
        assertArrayEquals(hex("240200fa0000000000000000000000000000000000000000001122334455667783aa7e80c000000083aa7e8100000000"),reply)
        request[0]=0x1b
        assertEquals(0x1c,CarPlayTiming.reply(request,1_250_000_000,1_500_000_000,clock)!![0].toInt())
        assertNull(CarPlayTiming.reply(request,1_250_000_000,1_500_000_000))
    }
    @Test fun timingFormatsKeepTheirOwnClockDomains(){
        val clock=CarPlayTiming.WallClock(anchorUnixMillis=1_700_000_000_000,anchorMonotonicNs=1_000_000_000)
        val request=ByteArray(32).also{it[0]=0x80.toByte();it[1]=0xd2.toByte()}
        assertArrayEquals(CarPlayTiming.reply(request,1_250_000_000,1_500_000_000),
            CarPlayTiming.reply(request,1_250_000_000,1_500_000_000,clock))
        assertNotEquals(CarPlayTiming.ntp(1_500_000_000),clock.ntp(1_500_000_000))
        val ntpRequest=ByteArray(48).also{it[0]=0x23}
        val response=CarPlayTiming.reply(ntpRequest,1_250_000_000,1_500_000_000,clock)!!
        assertEquals(clock.ntp(1_500_000_000),ByteBuffer.wrap(response).getLong(40))
    }
    @Test fun anchoredWallClockHandlesFractionCarryAndSamplesBeforeItsAnchor(){
        val clock=CarPlayTiming.WallClock(anchorUnixMillis=750,anchorMonotonicNs=1_000_000_000)
        assertEquals(0x83aa7e8100000000UL.toLong(),clock.ntp(1_250_000_000))
        assertEquals(0x83aa7e8080000000UL.toLong(),clock.ntp(750_000_000))
    }
    @Test fun timingRejectsTruncatedExtendedAndNonClientNtpDatagrams(){
        val clock=CarPlayTiming.WallClock(0,0)
        val request=ByteArray(48).also{it[0]=0x23}
        for(length in listOf(0,31,32,47,49,96))assertNull(CarPlayTiming.reply(request.copyOf(length),0,0,clock))
        for(first in listOf(0x03,0x13,0x2b,0x24,0x22,0x20)){
            request[0]=first.toByte()
            assertNull(CarPlayTiming.reply(request,0,0,clock))
        }
        val d2=ByteArray(32).also{it[0]=0x80.toByte();it[1]=0xd2.toByte()}
        assertNull(CarPlayTiming.reply(d2.copyOf(48),0,0,clock))
    }
    @Test fun siriAcceptsOnlyTheConfirmedPlistIntegerBranches(){
        fun command(value:Any?)=mapOf("type" to "requestSiri","params" to mapOf("siriAction" to value))
        assertEquals(2,SiriCommand.action(command(2)))
        assertEquals(3,SiriCommand.action(command(3L)))
        for(value in listOf("2","start",0,1,2.0,true,0x100000002L,null))assertNull(SiriCommand.action(command(value)))
        assertNull(SiriCommand.action(mapOf("type" to "requestSiri","siriAction" to 2)))
        assertNull(SiriCommand.action(mapOf("type" to "siriAction","params" to mapOf("siriAction" to 2))))
    }
}
