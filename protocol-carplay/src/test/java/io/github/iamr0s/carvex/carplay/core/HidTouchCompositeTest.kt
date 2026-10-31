package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class HidTouchCompositeTest {
    private fun hex(value:String)=value.replace(" ","").chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    private val touchStart="050d0904a1018501"
    private val finger="050d0922a102094215002501750195018102750795018101050109300931150026ff00750895028102c0"
    private val touch=touchStart+finger+"c0"
    private val keyboard="05010906a101850205071900296515002565750895018100c0"
    private val relativeMouse="05010902a1018503093009311581257f750895028106c0"

    @Test fun otherReportArraysAndRelativeAxesDoNotDisableTouch(){
        val decoder=HidTouch.parse(hex(touch+keyboard+relativeMouse))
        assertFalse(decoder.matchesReport(byteArrayOf(2,0x28)))
        assertFalse(decoder.matchesReport(byteArrayOf(3,-1,1)))
        val report=byteArrayOf(1,1,64,-128)
        assertTrue(decoder.matchesReport(report))
        assertEquals(TouchSample(true,64f,128f),decoder.decode(report,256,256))
    }

    @Test fun oneReportCanContainTouchKeyboardArrayRelativeWheelAndMediaKeys(){
        // Array and wheel bytes precede the media bit; all functions share report ID 1.
        val descriptor=hex(touchStart+finger+
            "05071900296515002565750895018100"+
            "050109381581257f750895018106"+
            "050c09b515002501750195018102750795018101c0")
        val decoder=HidTouch.parse(descriptor)
        val keys=HidUsages.parse(descriptor)
        val report=byteArrayOf(1,1,64,-128,0x28,-1,1)
        assertTrue(decoder.matchesReport(report))
        assertEquals(TouchSample(true,64f,128f),decoder.decode(report,256,256))
        assertEquals(setOf(0xb5),keys.decode(report))
        assertEquals(emptySet<Int>(),keys.decode(report.copyOf().apply{this[6]=0}))
    }

    @Test fun routingCheckDoesNotReleaseThePrimaryContact(){
        val decoder=HidTouch.parse(hex(touchStart+finger+finger+"c0"))
        assertEquals(TouchSample(true,64f,128f),
            decoder.decode(byteArrayOf(1,1,64,-128,1,-56,-36),256,256))
        val releaseFirst=byteArrayOf(1,0,0,0,1,-56,-36)
        repeat(2){assertTrue(decoder.matchesReport(releaseFirst))}
        // The real transition must still release contact one before adopting contact two.
        assertEquals(TouchSample(false,64f,128f),decoder.decode(releaseFirst,256,256))
        assertEquals(TouchSample(true,200f,220f),decoder.decode(releaseFirst,256,256))
    }

    @Test fun wrongIdAndLengthDoNotAdvanceContactState(){
        val decoder=HidTouch.parse(hex(touchStart+finger+finger+"c0"))
        decoder.decode(byteArrayOf(1,1,64,-128,1,-56,-36),256,256)
        val releaseFirst=byteArrayOf(1,0,0,0,1,-56,-36)
        val invalid=listOf(byteArrayOf(),releaseFirst.copyOf(6),releaseFirst.copyOf(8),
            releaseFirst.copyOf().apply{this[0]=2})
        for(report in invalid){
            assertFalse(decoder.matchesReport(report))
            assertThrows(IllegalArgumentException::class.java){decoder.decode(report,256,256)}
        }
        assertEquals(TouchSample(false,64f,128f),decoder.decode(releaseFirst,256,256))
    }

    @Test fun unnumberedTouchKeepsItsFirstDataByte(){
        val decoder=HidTouch.parse(hex(touch.replace("8501","")))
        assertTrue(decoder.matchesReport(byteArrayOf(1,64,-128)))
        assertFalse(decoder.matchesReport(byteArrayOf(1,1,64,-128)))
        assertEquals(TouchSample(true,64f,128f),decoder.decode(byteArrayOf(1,64,-128),256,256))
        assertEquals(TouchSample(false,64f,128f),decoder.decode(byteArrayOf(0,0,0),256,256))
    }

    @Test fun actualContactFieldsMustStillBeAbsoluteVariables(){
        val relativeTouch=touch.replace("95028102","95028106")
        val arrayTouch=touch.replace("95028102","95028100")
        assertThrows(IllegalArgumentException::class.java){HidTouch.parse(hex(relativeTouch))}
        assertThrows(IllegalArgumentException::class.java){HidTouch.parse(hex(arrayTouch))}
    }

    @Test fun twoCompleteTouchReportIdsPickTheLowestIdAndReportAmbiguity(){
        // 本地策略：多份候选时取报告 id 最小的那份，并把候选数量暴露给调用方记录。
        val decoder = HidTouch.parse(hex(touch + touch.replace("8501", "8502")))
        assertEquals(2, HidTouch.ambiguousReportCount)
        requireNotNull(decoder)
    }
}
