package io.github.iamr0s.carvex.sessioncore

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolTraceTest {
    @Test
    fun formatsStableFieldsAndReducesWhitespace() {
        assertEquals(
            "trace phase=IAP2 transport=RFCOMM direction=rx tx=0x1d01 outcome=accepted detail=fields_12",
            ProtocolTrace.event("IAP2", "RFCOMM", "rx", "0x1d01", "accepted", "fields 12"),
        )
    }

    @Test
    fun emptyDetailIsOmitted() {
        assertEquals(
            "trace phase=AIRPLAY transport=RTSP direction=tx tx=SETUP outcome=sent",
            ProtocolTrace.event("AIRPLAY", "RTSP", "tx", "SETUP", "sent", "  "),
        )
    }
}
