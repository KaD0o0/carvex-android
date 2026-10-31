package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class VideoDiagnosticLogTest {
    @Test fun stalledWritesHaveAStableKeyAndPreserveTheMeasuredAge(){
        val message=VideoDiagnosticLog.message(VideoDiagnosticLog.WRITE_STALLED_MS,8500)
        assertNotNull(message)
        assertTrue(message!!.contains("ageMs=8500"))
        assertTrue(message.contains("receiver playback not confirmed"))
    }

    @Test fun unrelatedCountersDoNotBecomePerFrameLogMessages(){
        assertNull(VideoDiagnosticLog.message("encoded",1))
        assertNull(VideoDiagnosticLog.message("queue-ms",12))
    }
}
