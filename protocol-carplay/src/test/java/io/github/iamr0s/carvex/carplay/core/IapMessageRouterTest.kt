package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class IapMessageRouterTest {
    @Test fun knownWirelessIdsRouteToTheWirelessFamily(){
        assertTrue(IapMessageRouter.classify(0x1d00,byteArrayOf(1,2)) is IapMessageRouter.Route.Wireless)
        assertTrue(IapMessageRouter.classify(0x5703,ByteArray(0)) is IapMessageRouter.Route.Wireless)
        assertTrue(IapMessageRouter.classify(0xaa01,byteArrayOf(9)) is IapMessageRouter.Route.Wireless)
        assertTrue(IapMessageRouter.classify(0xaa06,byteArrayOf(9)) is IapMessageRouter.Route.Wireless)
        assertTrue(IapMessageRouter.classify(0x1d01,byteArrayOf(1)) is IapMessageRouter.Route.Wireless)
    }

    @Test fun lingoPacketsAreRecognisedFromThePayloadItself(){
        // 无线族之外的 id，载荷是一个自洽的 0x55 包 → 文档描述的 lingo 协议族
        val route=IapMessageRouter.classify(0x0004,IapLingo.build(0,0x4b,byteArrayOf(3)))
        val lingo=(route as IapMessageRouter.Route.Lingo).message
        assertEquals(0,lingo.lingo)
        assertEquals(0x4b,lingo.command)
        assertArrayEquals(byteArrayOf(3),lingo.payload)
    }

    /** 只看首字节会把"刚好以 0x55 开头"的其它载荷误判成 lingo，所以判据是整包自洽。 */
    @Test fun aLeading55ByteAloneIsNotALingoPacket(){
        assertTrue(IapMessageRouter.classify(0x0004,byteArrayOf(0x55,0x09,0x00,0x00)) is IapMessageRouter.Route.Unknown)
        assertTrue(IapMessageRouter.classify(0x0004,ByteArray(0)) is IapMessageRouter.Route.Unknown)
        assertNull(IapMessageRouter.lingoMessage(byteArrayOf(0x55,0x02)))
    }

    /** 未知消息是常态而不是错误：分类不得抛异常，交给调用方记录后继续。 */
    @Test fun unknownMessagesAreClassifiedNotThrown(){
        val route=IapMessageRouter.classify(0x7fff,byteArrayOf(1,2,3)) as IapMessageRouter.Route.Unknown
        assertEquals(0x7fff,route.id)
        assertEquals(3,route.payloadBytes)
        assertNull(IapMessageRouter.lingoMessage(byteArrayOf(1,2,3)))
    }
}
