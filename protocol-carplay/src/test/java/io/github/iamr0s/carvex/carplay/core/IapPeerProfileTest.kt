package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class IapPeerProfileTest {
    private val nothing=PeerEvidence()
    private fun fields(id:Int)=IapCommand.Fields(id,IapFields(emptyList()))
    private fun lingo(id:Int=0x0004,lingo:Int=0,command:Int=0x4b)=IapCommand.Lingo(id,IapLingo.Message(lingo,command,null,byteArrayOf()))

    @Test fun nothingKnownFallsBackToUnknown(){
        val profile=IapPeerProfiles.select(nothing)
        assertEquals("unknown",profile.name)
        assertEquals(IapAuthentication.Action.RECORD,profile.lingoAuthenticationAction(0,0x1a))
        assertEquals(IapAuthentication.Action.RECORD,profile.messageAuthenticationAction(0xaa01))
        assertEquals(IapLingoCatalog.Expectation.UNCONFIRMED,profile.lingoExpectation(0,0x0f))
    }

    /** 版本标识单独不足以判定行为族：文档明确 210.81 只是本车所带 SDK 的版本。 */
    @Test fun versionAloneDoesNotSelectAProfile(){
        val versionOnly=PeerEvidence(PeerVersion(airplayProtocol="210.81",airplaySource="210.81",controlSession=2))
        assertEquals("unknown",IapPeerProfiles.select(versionOnly).name)
    }

    @Test fun observedWirelessMessageIdsSelectTheWirelessProfile(){
        assertEquals("wireless-carplay",IapPeerProfiles.select(nothing.observing(0x1d01,byteArrayOf(1,2))).name)
        val authSerial=IapPeerProfiles.select(nothing.observing(0xaa06,byteArrayOf()))
        assertEquals("wireless-carplay",authSerial.name)
        assertEquals(IapAuthentication.Action.RECORD,authSerial.messageAuthenticationAction(0xaa06))
    }

    @Test fun aLingoPayloadSelectsTheDocumentedProfile(){
        val evidence=nothing.observing(0x0004,IapLingo.build(0,0x4b,byteArrayOf(3)))
        assertTrue(evidence.sawLingoPayload)
        assertEquals("documented-wired",IapPeerProfiles.select(evidence).name)
    }

    /** 证据是累积的：先见到无线 id、后见到 lingo 载荷，选中的实现会跟着换。 */
    @Test fun evidenceAccumulatesAndTheProfileFollowsIt(){
        val evidence=nothing.observing(0x1d01,byteArrayOf(1)).observing(0x0004,IapLingo.build(0,0x0f))
        assertEquals(setOf(0x1d01,0x0004),evidence.observedMessageIds)
        assertTrue(evidence.sawLingoPayload)
        assertEquals("documented-wired",IapPeerProfiles.select(evidence).name)
    }

    /** 两个实现必须在行为上真的不同，而不是只换了个名字：各自只认自己那一族。 */
    @Test fun theTwoProfilesOnlyClaimTheirOwnFamily(){
        val wired=IapPeerProfiles.documentedWired
        val wireless=IapPeerProfiles.wireless
        assertTrue(wired.classify(lingo()) is IapMessageRouter.Route.Lingo)
        assertTrue(wired.classify(fields(0x1d01)) is IapMessageRouter.Route.Unknown)
        assertTrue(wireless.classify(fields(0x1d01)) is IapMessageRouter.Route.Wireless)
        assertTrue(wireless.classify(lingo()) is IapMessageRouter.Route.Unknown)
    }

    /** 认证方式才是两族最实质的差别：文档族要客户端签名（需 MFi 芯片），无线族只验证车机、从不签。 */
    @Test fun onlyTheDocumentedProfileEverAsksForASignature(){
        val wired=IapPeerProfiles.documentedWired
        val wireless=IapPeerProfiles.wireless
        assertEquals(IapAuthentication.Action.SIGN,wired.lingoAuthenticationAction(0,0x1a))
        assertEquals(IapAuthentication.Action.RECORD,wireless.lingoAuthenticationAction(0,0x1a))
        assertEquals(IapAuthentication.Action.ACCEPT,wireless.messageAuthenticationAction(0xaa01))
        assertEquals(IapAuthentication.Action.ACCEPT,wireless.messageAuthenticationAction(0xaa03))
        assertEquals(IapAuthentication.Action.RECORD,wired.messageAuthenticationAction(0xaa01))
    }

    /** 任何 profile 都不因为"不认识这个 EA 名字"而丢弃 —— 名字和 ID 两种形态都接受。 */
    @Test fun everyProfileAcceptsAnUnseenEaName(){
        listOf(IapPeerProfiles.unknown,IapPeerProfiles.documentedWired,IapPeerProfiles.wireless).forEach{
            assertTrue(it.name,it.eaSession("com.example.never-seen") is EaSessionPolicy.Decision.Accept)
            assertTrue(it.name,it.eaSession("0x09") is EaSessionPolicy.Decision.Accept)
        }
    }

    /** 兜底实现不认任何协议族，但也不拒绝：未知消息记录后继续。 */
    @Test fun unknownProfilePresetsNothingButRejectsNothing(){
        val profile=IapPeerProfiles.unknown
        assertTrue(profile.classify(fields(0x1d01)) is IapMessageRouter.Route.Unknown)
        assertTrue(profile.classify(lingo()) is IapMessageRouter.Route.Unknown)
        assertTrue(profile.eaSession(null) is EaSessionPolicy.Decision.Reject)
    }
}
