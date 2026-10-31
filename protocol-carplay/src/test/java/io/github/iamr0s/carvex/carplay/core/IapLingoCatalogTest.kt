package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

/** gap-closure §3.1 的 (lingo, command) 表 —— 只描述对端会发什么，不描述客户端该怎么应答。 */
class IapLingoCatalogTest {
    @Test fun tableHasNoDuplicateLingoCommandPairs(){
        val keys=IapLingoCatalog.commands.map{it.lingo to it.command}
        assertEquals(keys.size,keys.distinct().size)
    }

    @Test fun documentedQueriesAreMarkedAnswerable(){
        assertEquals(IapLingoCatalog.Expectation.ANSWER,IapLingoCatalog.expectation(0,0x0f))
        assertEquals(IapLingoCatalog.Expectation.ANSWER,IapLingoCatalog.expectation(0,0x4b))
        assertEquals(IapLingoCatalog.Expectation.ANSWER,IapLingoCatalog.expectation(3,0x07))
        assertEquals(IapLingoCatalog.Expectation.ACCEPT,IapLingoCatalog.expectation(0,0x38))
        assertEquals(IapLingoCatalog.Expectation.ACCEPT,IapLingoCatalog.expectation(10,0x03))
    }

    /** 文档没写明方向的条目（例如 HID 族）不得被当成已知行为使用。 */
    @Test fun undocumentedDirectionStaysUnconfirmed(){
        assertEquals(IapLingoCatalog.Expectation.UNCONFIRMED,IapLingoCatalog.expectation(2,0x0f))
        assertEquals(IapLingoCatalog.Expectation.UNCONFIRMED,IapLingoCatalog.expectation(9,0x01))
        assertNull(IapLingoCatalog.find(9,0x01))
    }

    /** 处置要说出"还缺什么"，而不是把缺口当成已实现：三条需要客户端应答的查询，文档都只给了
     *  调用点、没给应答载荷；唯一有完整证据的那条（索取认证信息）缺的是芯片。 */
    @Test fun dutiesNameWhatIsMissingInsteadOfInventingAReply(){
        assertEquals(IapLingoCatalog.Duty.ANSWER_REQUIRES_MFI_CHIP,IapLingoCatalog.duty(0,0x1a))
        listOf(0x0f,0x4b,0x13).forEach{command->
            assertEquals("command=$command",IapLingoCatalog.Duty.ANSWER_WITHOUT_EVIDENCE,IapLingoCatalog.duty(0,command))
        }
        assertEquals(IapLingoCatalog.Duty.ANSWER_WITHOUT_EVIDENCE,IapLingoCatalog.duty(3,0x07))
        assertEquals(IapLingoCatalog.Duty.ACCEPT,IapLingoCatalog.duty(0,0x15))
        assertEquals(IapLingoCatalog.Duty.ACCEPT,IapLingoCatalog.duty(0,0x38))
        assertEquals(IapLingoCatalog.Duty.UNCONFIRMED,IapLingoCatalog.duty(2,0x10))
        assertEquals(IapLingoCatalog.Duty.UNCONFIRMED,IapLingoCatalog.duty(9,0x01))
    }

    /** lingo 名表：固件逆出的 5 个片段必须全部落在第三方全表内（gap-closure §3.3 的互证）。 */
    @Test fun lingoNamesCoverBothTheFirmwareFragmentsAndTheFullTable(){
        assertEquals("General",IapLingoCatalog.name(0))
        assertEquals("Simple Remote",IapLingoCatalog.name(2))
        assertEquals("Display Remote",IapLingoCatalog.name(3))
        assertEquals("Extended Interface",IapLingoCatalog.name(4))
        assertEquals("Digital Audio",IapLingoCatalog.name(10))
        listOf(0,2,3,4,10).forEach{assertNotEquals("lingo$it",IapLingoCatalog.name(it))}
        assertEquals("lingo99",IapLingoCatalog.name(99))
    }
}
