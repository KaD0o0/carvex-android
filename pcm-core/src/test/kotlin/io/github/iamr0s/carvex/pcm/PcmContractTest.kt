package io.github.iamr0s.carvex.pcm
import org.junit.Assert.*
import org.junit.Test
class PcmContractTest {
    @Test fun callbackFormatDeterminesFrameAlignment(){
        val mono=PcmFormat(16000,1);val stereo=PcmFormat(48000,2)
        assertTrue(mono.accepts(640));assertTrue(stereo.accepts(3840))
        assertFalse(stereo.accepts(642));assertFalse(mono.accepts(641))
        assertFalse(mono.accepts(0));assertFalse(mono.accepts(32770))
        assertNotEquals(mono,stereo)
    }
    @Test fun unsupportedFormatsCannotBeAdvertised(){
        assertThrows(IllegalArgumentException::class.java){PcmFormat(48000,2,3)}
        assertThrows(IllegalArgumentException::class.java){PcmFormat(0,1)}
        assertThrows(IllegalArgumentException::class.java){PcmFormat(48000,6)}
    }
}
