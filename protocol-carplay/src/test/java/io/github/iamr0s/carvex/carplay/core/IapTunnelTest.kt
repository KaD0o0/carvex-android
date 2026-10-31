package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

/** 第 13 章 §2.6b：type=130 包的 32 字节头。 */
class IapTunnelTest {
    @Test fun type130RequiresExactIapChannelCapability(){
        assertFalse(IapTunnelSetupRequest.enabledBy(null))
        assertFalse(IapTunnelSetupRequest.enabledBy(emptyList<Any>()))
        assertFalse(IapTunnelSetupRequest.enabledBy(listOf("iapChannel",1,"other")))
        assertTrue(IapTunnelSetupRequest.enabledBy(listOf("other","iAPChannel")))
    }

    @Test fun setupRequestUsesStringChannelIdAndCanonicalClientUuid(){
        val request=IapTunnelSetupRequest.build(
            deviceId="00:11:22:33:44:55",seed=0x1122334455667788L,
            clientUUID="550e8400-e29b-41d4-a716-446655440000")
        val decoded=Plist.dictionary(Plist.encode(request))
        val stream=(decoded["streams"] as List<*>).single() as Map<*,*>
        assertEquals(130L,stream["type"])
        assertEquals(IapTunnel.CLIENT_TYPE_UUID,stream["clientTypeUUID"])
        assertEquals("550E8400-E29B-41D4-A716-446655440000",stream["clientUUID"])
        assertEquals("00:11:22:33:44:55-RCS-1",stream["channelID"])
        assertEquals(2L,stream["controlType"])
        assertEquals(true,stream["wantsDedicatedSocket"])
        assertEquals(true,stream["sendMessageAsIs"])
        assertEquals(0x1122334455667788L,stream["seed"])
    }

    @Test fun type130ResponseRequiresOneTypedStreamAndKeepsZeroPortAsShared(){
        val reply=IapTunnelSetupResponse.parse(mapOf("streams" to listOf(
            mapOf("type" to 130,"streamID" to 77L,"dataPort" to 0L))))
        assertEquals(77L,reply.streamId)
        assertNull(reply.dataPort)
    }

    @Test fun type130ResponseRejectsMissingOrAmbiguousStreams(){
        assertThrows(IllegalArgumentException::class.java){
            IapTunnelSetupResponse.parse(emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java){
            IapTunnelSetupResponse.parse(mapOf("streams" to listOf(
                mapOf("type" to 130,"streamID" to 1L),mapOf("type" to 130,"streamID" to 2L))))
        }
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xff) shl 24) or ((b[at + 1].toLong() and 0xff) shl 16) or
            ((b[at + 2].toLong() and 0xff) shl 8) or (b[at + 3].toLong() and 0xff)

    private fun u64(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xff)
        return v
    }

    @Test fun packageHeaderMatchesFirmwareLayout() {
        val data = byteArrayOf(0x66, 0x5a, 0x00, 0x06)
        val p = IapTunnel.packageBytes(data)
        assertEquals(36, p.size)
        assertEquals(36L, u32(p, 0))                 // 0..4 总长
        assertEquals(0x6173796eL, u32(p, 4))         // 4..8  "asyn"
        assertEquals(0L, u64(p, 8))                  // 8..16 group=0（专用路径）
        assertEquals(0x636f6d6dL, u32(p, 16))        // 16..20 "comm"
        assertEquals(0L, u64(p, 20))                 // 20..28 replyToken=0
        assertEquals(0L, u32(p, 28))                 // 28..32 status=0
        assertEquals(data.toList(), p.copyOfRange(32, 36).toList())
    }

    @Test fun groupIsBigEndianAndOnlyUsedWhenNonZero() {
        val p = IapTunnel.packageBytes(byteArrayOf(1), group = 0x1122334455667788L)
        assertEquals(0x1122334455667788L, u64(p, 8))
    }
}
