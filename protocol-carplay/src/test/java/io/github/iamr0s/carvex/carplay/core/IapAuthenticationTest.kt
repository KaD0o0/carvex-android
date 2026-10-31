package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class IapAuthenticationTest {
    @Test fun certificateRequestUsesAa06OnlyAfterTheAccessoryAdvertisesIt(){
        assertArrayEquals(byteArrayOf(0x40,0x40,0,6,0xaa.toByte(),0),
            IapWire.csm(0xaa00,IapAuthentication.certificateRequestFields(null)))
        assertTrue(IapAuthentication.certificateRequestFields(emptySet()).isEmpty())
        assertTrue(IapAuthentication.certificateRequestFields(setOf(0xaa01,0xaa03)).isEmpty())
        assertArrayEquals(byteArrayOf(0x40,0x40,0,10,0xaa.toByte(),0,0,4,0,0),
            IapWire.csm(0xaa00,IapAuthentication.certificateRequestFields(setOf(0xaa06))))
    }

    @Test fun u2iwSerialLengthsAreOnlyHintsAndUnknownShapesAreNotGuessed(){
        assertTrue(IapAuthentication.u2iwModernAuthHint(IapFields(listOf(0 to ByteArray(16)))))
        listOf(0x34,0x35).forEach{
            assertTrue(IapAuthentication.u2iwModernAuthHint(IapFields(listOf(0 to ByteArray(32).apply{this[0]=it.toByte()}))))
        }
        assertFalse(IapAuthentication.u2iwModernAuthHint(IapFields(listOf(0 to ByteArray(32)))))
        assertFalse(IapAuthentication.u2iwModernAuthHint(IapFields(listOf(1 to ByteArray(16)))))
        assertFalse(IapAuthentication.u2iwModernAuthHint(IapFields(listOf(0 to ByteArray(16),0 to ByteArray(16)))))
    }

    @Test fun accessoryReportedAuthenticationIsAcceptedNotSigned(){
        assertEquals(IapAuthentication.Action.ACCEPT,IapAuthentication.action(0,0x15))
        assertEquals(IapAuthentication.Action.ACCEPT,IapAuthentication.action(0,0x18))
    }

    @Test fun deviceAuthenticationInfoRequestNeedsChip(){
        assertEquals(IapAuthentication.Action.SIGN,IapAuthentication.action(0,0x1a))
    }

    /** 别的 lingo、别的命令一律记录后继续，不当作已知行为。 */
    @Test fun unknownCommandsAreRecordedNotGuessed(){
        assertEquals(IapAuthentication.Action.RECORD,IapAuthentication.action(0,0x99))
        assertEquals(IapAuthentication.Action.RECORD,IapAuthentication.action(4,0x1c))
        assertEquals(IapAuthentication.Action.RECORD,IapAuthentication.action(2,0x1a))
    }

    /** 没有 MFi 芯片时必须显式失败，不能返回空签名、也不能伪造成功（spec §2.3「不可绕」）。 */
    @Test fun signingWithoutChipFailsLoudly(){
        val error=assertThrows(UnsupportedOperationException::class.java){
            IapAuthentication.sign(null,byteArrayOf(1,2,3))
        }
        assertTrue(error.message!!.contains("MFi"))
        val chip=IapAuthentication.Chip{challenge->ByteArray(64){challenge[0]}}
        assertArrayEquals(ByteArray(64){1},IapAuthentication.sign(chip,byteArrayOf(1,2,3)))
    }
}
