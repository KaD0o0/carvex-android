package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class AvcRecoveryConfigTest {
    @Test fun recoveryUsesTheLatestCompleteConfigurationAndOwnsItsSnapshot(){
        val configuration=AvcConfiguration()
        val sps=byteArrayOf(0x67,0x64,0,0x1f)
        val pps=byteArrayOf(0x68,1)
        assertNull(configuration.current())
        configuration.ingest(sps)
        assertNull(configuration.takeChanged())
        assertNull(configuration.current())
        configuration.ingest(pps)
        val first=configuration.takeChanged()!!
        assertArrayEquals(first,configuration.current())
        first.fill(0)
        configuration.current()!!.fill(0)
        assertArrayEquals(VideoWire.avcc(sps,pps),configuration.current())
        val nextPps=byteArrayOf(0x68,2)
        configuration.ingest(nextPps)
        assertArrayEquals(VideoWire.avcc(sps,nextPps),configuration.takeChanged())
        assertArrayEquals(VideoWire.avcc(sps,nextPps),configuration.current())
    }
}
