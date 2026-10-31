package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

/** 无线配对的消息只用于**识别**：本实现不做 OOBB/蓝牙配对，但必须认得出车机在请求它，
 *  否则这种情况只会表现成握手超时。 */
class IapWirelessPairingTest {
    @Test fun idsMatchTheTwoFamiliesInTheThirdPartyTable(){
        assertEquals(9,IapWirelessPairing.Message.ids.size)
        assertTrue(0x00b0 in IapWirelessPairing.Message.ids)
        assertTrue(0x00b4 in IapWirelessPairing.Message.ids)
        assertTrue(0x0b00 in IapWirelessPairing.Message.ids)
        assertTrue(0x0b03 in IapWirelessPairing.Message.ids)
        assertEquals("OOBBTPairingAccessoryInformation",IapWirelessPairing.Message.at(0x00b1)!!.label)
        assertEquals("BluetoothPairingStatus",IapWirelessPairing.Message.at(0x0b02)!!.label)
        assertNull(IapWirelessPairing.Message.at(0x00b5))
    }

    /** 它们属于无线族：分发与实现选择都要把它们算进无线那一支，而不是当成未知消息。 */
    @Test fun pairingMessagesBelongToTheWirelessFamily(){
        assertTrue(0x00b1 in IapMessageRouter.wirelessIds)
        assertTrue(0x0b00 in IapMessageRouter.wirelessIds)
        assertTrue(IapMessageRouter.classify(0x00b1,byteArrayOf(1,2)) is IapMessageRouter.Route.Wireless)
        assertTrue(IapPeerProfiles.wireless.classify(IapCommand.Fields(0x0b02,IapFields(emptyList())))
            is IapMessageRouter.Route.Wireless)
        assertEquals("wireless-carplay",IapPeerProfiles.select(PeerEvidence().observing(0x0b00,byteArrayOf(1))).name)
    }

    /** 拒绝理由必须说清"缺什么能力"，而不是只说一句不支持。 */
    @Test fun unsupportedExplanationNamesTheMissingAbility(){
        val text=IapWirelessPairing.unsupported(0x00b0)!!
        assertTrue(text,"StartOOBBTPairing" in text)
        assertTrue(text,"P-192/P-256" in text)
        assertTrue(text,"handshake timeout" in text)
        assertNull(IapWirelessPairing.unsupported(0x1234))
    }
}
