package io.github.iamr0s.carvex.carplay

import io.github.iamr0s.carvex.carplay.core.IapFields
import io.github.iamr0s.carvex.carplay.core.IapWire
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Wi-Fi credentials can be reused; a new 43/01 always provides a new session snapshot. */
class BootstrapConfigTest {
    @Test fun wirelessOptionalRequestsRequireBothDeclarationDirections() {
        assertTrue(Bootstrap.declarationPairComplete(0x5702,0x5703,setOf(0x5702),setOf(0x5703)))
        assertFalse(Bootstrap.declarationPairComplete(0x5702,0x5703,setOf(0x5702),emptySet()))
        assertFalse(Bootstrap.declarationPairComplete(0x4300,0x4301,emptySet(),setOf(0x4301)))
    }
    private fun start(ssid:String="CAR-1",password:String="password1",security:Int=3,address:String="192.168.1.2",
        port:Int=7000,receiver:String="receiver-id",publicKey:String?=null):IapFields {
        val wifi=IapWire.fields(listOf(
            0 to IapWire.cstring(ssid),1 to IapWire.cstring(password),
            3 to IapWire.cstring(address),4 to byteArrayOf(security.toByte())))
        val list=mutableListOf(1 to wifi,2 to ByteBuffer.allocate(4).putInt(port).array(),3 to IapWire.cstring(receiver))
        if(publicKey!=null)list+=4 to IapWire.cstring(publicKey)
        return IapFields(list.toList())
    }
    @Test fun validConfigurationIsParsed(){
        val bootstrap=Bootstrap.wifiConfig(start(),null,null){}
        assertEquals("CAR-1",bootstrap.ssid)
        assertEquals("password1",bootstrap.password)
        assertEquals(3,bootstrap.security)
        assertEquals("192.168.1.2",bootstrap.address)
        assertEquals(7000,bootstrap.port)
        assertEquals("receiver-id",bootstrap.receiver)
    }
    /** Channel stays diagnostic; mutualAuth is retained without implying authentication. */
    @Test fun optionalChannelAndMutualAuthPreserveTheSourceSnapshot(){
        val wifi=IapWire.fields(listOf(
            0 to IapWire.cstring("CAR-1"),1 to IapWire.cstring("password1"),
            2 to byteArrayOf(11),3 to IapWire.cstring("192.168.1.2"),4 to byteArrayOf(3)))
        val fields=IapFields(listOf(
            1 to wifi,2 to ByteBuffer.allocate(4).putInt(7000).array(),
            3 to IapWire.cstring("receiver-id"),8 to byteArrayOf(1)))
        val logs=mutableListOf<String>()
        val bootstrap=Bootstrap.wifiConfig(fields,null,null){logs+=it}
        assertEquals("CAR-1",bootstrap.ssid)
        assertEquals(7000,bootstrap.port)
        assertTrue(logs.any{it.contains("channel=11")&&it.contains("mutualAuth=true")})
        assertEquals(true,bootstrap.startSession!!.startSessionPeerInfo!!.supportsMutualAuthentication)
        assertSame(bootstrap.endpoint,bootstrap.startSession)
        assertNull(bootstrap.authentication)
    }
    @Test fun startVersionsRemainDistinctFromDiscoveryAndSurviveEndpointSelection(){
        val peer=StartSessionPeerInfo("receiver-source", "receiver-sdk", false)
        val fields=IapFields(start().ordered+listOf(5 to IapWire.cstring(peer.sourceVersion!!),
            6 to IapWire.cstring(peer.sdkVersion!!),8 to byteArrayOf(0)))
        val bootstrap=Bootstrap.wifiConfig(fields,null,null){}
        assertEquals(peer,bootstrap.endpoint!!.startSessionPeerInfo)
        assertEquals(peer,bootstrap.startSession!!.startSessionPeerInfo)
        assertNull(bootstrap.endpoint!!.metadata)
        val metadata=ReceiverMetadata(null,null,null,sourceVersion="bonjour-source")
        val resolved=bootstrap.resolved("192.0.2.2",7001,"discovered-id",null,metadata)
        assertEquals("bonjour-source",resolved.endpoint!!.metadata!!.sourceVersion)
        assertNull(resolved.endpoint!!.startSessionPeerInfo)
        assertEquals(peer,resolved.startSession!!.startSessionPeerInfo)
        assertEquals("receiver-id",resolved.startSession!!.receiver)
        assertNull(resolved.authentication)
        val next=Bootstrap.wifiConfig(start(),null,resolved){}
        assertEquals(StartSessionPeerInfo(),next.startSession!!.startSessionPeerInfo)
        assertNull(next.authentication)
        assertNull(next.endpoint!!.metadata)
        assertTrue(bootstrap.sameNetworkAs(next))
    }
    @Test fun absentFalseAndTrueMutualAuthRemainDistinct(){
        assertNull(Bootstrap.wifiConfig(start(),null,null){}.startSession!!.startSessionPeerInfo!!.supportsMutualAuthentication)
        for(value in 0..1){
            val result=Bootstrap.wifiConfig(replace(start(),8,byteArrayOf(value.toByte())),null,null){}
            assertEquals(value==1,result.startSession!!.startSessionPeerInfo!!.supportsMutualAuthentication)
        }
    }
    @Test fun peerVersionAndBooleanFieldsRejectMalformedKnownValues(){
        for(id in listOf(5,6)){
            for(raw in listOf(byteArrayOf(),"no-terminator".toByteArray(),byteArrayOf(65,0,66)))
                failsAt(replace(start(),id,raw),id.toString(),StartSessionProblem.INVALID_TEXT)
        }
        for(raw in listOf(byteArrayOf(),byteArrayOf(0,1)))
            failsAt(replace(start(),8,raw),"8",StartSessionProblem.INVALID_WIDTH)
        for(value in listOf(2,255))
            failsAt(replace(start(),8,byteArrayOf(value.toByte())),"8",StartSessionProblem.INVALID_BOOLEAN)
    }
    @Test fun repeatedPeerScalarsReplaceInWireOrderButDoNotHideEarlierInvalidValues(){
        val result=Bootstrap.wifiConfig(IapFields(start().ordered+listOf(
            5 to IapWire.cstring("first"),5 to IapWire.cstring("last"),
            6 to IapWire.cstring("sdk"),6 to IapWire.cstring(""),
            8 to byteArrayOf(1),8 to byteArrayOf(0))),null,null){}
        assertEquals(StartSessionPeerInfo("last","",false),result.startSession!!.startSessionPeerInfo)
        failsAt(IapFields(start().ordered+listOf(8 to byteArrayOf(2),8 to byteArrayOf(1))),
            "8",StartSessionProblem.INVALID_BOOLEAN)
        failsAt(IapFields(start().ordered+listOf(5 to byteArrayOf(65),5 to IapWire.cstring("valid"))),
            "5",StartSessionProblem.INVALID_TEXT)
    }
    @Test fun peerVersionDiagnosticsDoNotPrintUntrustedValues(){
        val logs=mutableListOf<String>()
        val result=Bootstrap.wifiConfig(IapFields(start().ordered+listOf(
            5 to IapWire.cstring("source-secret\nline"),6 to IapWire.cstring("sdk-secret"))),null,null){logs+=it}
        val text=logs.joinToString()+result.startSession!!.startSessionPeerInfo
        assertFalse(text.contains("source-secret"));assertFalse(text.contains("sdk-secret"))
    }
    @Test fun publicKeyIsOnlyAcceptedAsFullHex(){
        val key="ab".repeat(32)
        assertEquals(32,Bootstrap.wifiConfig(start(publicKey=key),null,null){}.publicKey?.size)
        assertThrows(IllegalArgumentException::class.java){Bootstrap.wifiConfig(start(publicKey="zz".repeat(32)),null,null){}}
    }
    @Test fun experimentalCompatibilityIgnoresUnsupportedOptionalKeyButPreservesValidPins(){
        for(value in listOf("", "unrecognized-key-format", "zz".repeat(32))){
            val logs=mutableListOf<String>()
            val result=Bootstrap.wifiConfig(start(publicKey=value),null,null,experimentalCompatibility=true){logs+=it}
            assertNull(result.publicKey)
            assertTrue(logs.any{it.contains("optional public-key hint ignored")})
            assertFalse(logs.joinToString().contains("unrecognized-key-format"))
        }
        val first=Bootstrap.wifiConfig(start(publicKey="ab".repeat(32)),null,null,true){}
        val second=Bootstrap.wifiConfig(start(publicKey="cd".repeat(32)),null,first,true){}
        assertArrayEquals(ByteArray(32){0xab.toByte()},first.publicKey)
        assertArrayEquals(ByteArray(32){0xcd.toByte()},second.publicKey)
        assertThrows(IllegalArgumentException::class.java){io.github.iamr0s.carvex.carplay.core.ReceiverIdentity.reconcile(first.publicKey,second.publicKey)}
        val pin=io.github.iamr0s.carvex.carplay.core.PeerIdentity("pair-id",first.publicKey!!)
        assertThrows(io.github.iamr0s.carvex.carplay.core.SecurityFailure::class.java){
            io.github.iamr0s.carvex.carplay.core.PairingIdentityBinding.requirePeer(pin.identifier,second.publicKey,pin)
        }
    }
    @Test fun experimentalCompatibilityNormalizesAddressesBeforeTheConnectionPath(){
        val config=Bootstrap.wifiConfig(start(address=" 192.168.001.002 "),null,null,true){}
        assertEquals("192.168.1.2",config.address)
        assertEquals("192.168.1.2",io.github.iamr0s.carvex.carplay.core.IpLiteral.parse(config.address).address.hostAddress)
        val scoped=Bootstrap.wifiConfig(start(address="[fe80::1%accessory_wifi]"),null,null,true){}
        assertNull(io.github.iamr0s.carvex.carplay.core.IpLiteral.parse(scoped.address).scope)
        assertTrue(io.github.iamr0s.carvex.carplay.core.IpLiteral.parse(scoped.address).address.isLinkLocalAddress)
    }
    @Test fun invalidExtraCandidateDoesNotDiscardUsableAddressInExperiment(){
        val original=start(address="bad-candidate")
        val wifi=IapWire.parseFieldList(requireNotNull(original[1]))
        val config=replace(original,1,IapWire.fields(wifi.ordered+listOf(3 to IapWire.cstring("192.168.001.002"))))
        val logs=mutableListOf<String>()
        val parsed=Bootstrap.wifiConfig(config,null,null,true){logs+=it}
        assertEquals(listOf("192.168.1.2"),parsed.endpoint!!.addresses)
        assertTrue(logs.any{it.contains("address candidate skipped")})
        assertFalse(logs.joinToString().contains("bad-candidate"))
        assertThrows(StartSessionConfigurationException::class.java){Bootstrap.wifiConfig(start(address="bad-candidate"),null,null,true){}}
    }
    @Test fun compatibilityDoesNotInventPortSecurityOrIgnoreCredentialConflicts(){
        for(port in listOf(0,65536))assertThrows(StartSessionConfigurationException::class.java){Bootstrap.wifiConfig(start(port=port),null,null,true){}}
        assertThrows(StartSessionConfigurationException::class.java){Bootstrap.wifiConfig(replace(start(),2,byteArrayOf(0x1b,0x58)),null,null,true){}}
        assertThrows(IllegalArgumentException::class.java){Bootstrap.wifiConfig(start(security=1),null,null,true){}}
        val announcement=io.github.iamr0s.carvex.carplay.core.WifiAnnouncement("different","password1",2,149)
        assertThrows(IllegalArgumentException::class.java){Bootstrap.wifiConfig(start(),announcement,null,true){}}
    }
    private fun replace(start:IapFields,id:Int,value:ByteArray)=IapFields(start.ordered.filter{it.first!=id}+listOf(id to value))
    private fun replaceWireless(start:IapFields,id:Int,value:ByteArray):IapFields {
        val group=IapWire.parseFieldList(requireNotNull(start[1]))
        return replace(start,1,IapWire.fields(group.ordered.filter{it.first!=id}+listOf(id to value)))
    }
    private fun failsAt(config:IapFields,path:String,problem:StartSessionProblem){
        val error=assertThrows(StartSessionConfigurationException::class.java){Bootstrap.wifiConfig(config,null,null){}}
        assertEquals(path,error.fieldPath);assertEquals(problem,error.problem)
    }
    @Test fun failuresAfterCredentialMatchIdentifyTheSpecificField(){
        failsAt(start(address="bad.address"),"1.3",StartSessionProblem.INVALID_ADDRESS)
        failsAt(start(address="192.168.1.999"),"1.3",StartSessionProblem.INVALID_ADDRESS)
        failsAt(replaceWireless(start(),4,byteArrayOf(2,0)),"1.4",StartSessionProblem.INVALID_WIDTH)
        failsAt(replace(start(),2,byteArrayOf(0x1b,0x58)),"2",StartSessionProblem.INVALID_WIDTH)
        failsAt(start(port=0),"2",StartSessionProblem.INVALID_PORT)
        failsAt(start(receiver=""),"3",StartSessionProblem.INVALID_IDENTIFIER)
        failsAt(start(publicKey="not-hex"),"4",StartSessionProblem.INVALID_PUBLIC_KEY)
    }
    @Test fun fieldDiagnosticsExposeShapesWithoutCredentialsOrIdentityContents(){
        val logs=mutableListOf<String>()
        val secretKey="ab".repeat(32)
        Bootstrap.wifiConfig(start(publicKey=secretKey),null,null){logs+=it}
        assertTrue(logs.any{it.startsWith("4301 field lengths=")})
        assertTrue(logs.any{it.startsWith("4301 wireless field lengths=")})
        assertTrue(logs.any{it.contains("endpoint fields validated")})
        for(secret in listOf("CAR-1","password1","192.168.1.2","receiver-id",secretKey))assertFalse(logs.joinToString().contains(secret))
        val error=assertThrows(StartSessionConfigurationException::class.java){Bootstrap.wifiConfig(start(publicKey="secret-invalid-key"),null,null){}}
        assertFalse(error.message!!.contains("secret-invalid-key"))
    }
    @Test fun repeatedAddressesAreKeptAndDuplicateScalarReplaces(){
        val original=start()
        val wifi=IapWire.parseFieldList(requireNotNull(original[1]))
        val multiple=replace(original,1,IapWire.fields(wifi.ordered+listOf(3 to IapWire.cstring("fe80::1"))))
        val parsed=Bootstrap.wifiConfig(multiple,null,null){}
        assertEquals(listOf("192.168.1.2","fe80::1"),parsed.endpoint!!.addresses)
        assertEquals(parsed.endpoint!!.addresses,parsed.startSession!!.addresses)
        val repeated=Bootstrap.wifiConfig(IapFields(original.ordered+listOf(2 to IapWire.be32(7001))),null,null){}
        assertEquals(7001,repeated.port)
        failsAt(IapFields(listOf(2 to byteArrayOf(1))+original.ordered),"2",StartSessionProblem.INVALID_WIDTH)
    }
    @Test fun syntaxValidationPrecedesMissingLocalEndpointRequirements(){
        failsAt(IapFields(listOf(1 to IapWire.fields(emptyList()),2 to byteArrayOf(1))),"2",StartSessionProblem.INVALID_WIDTH)
        failsAt(replaceWireless(start(),2,byteArrayOf(0,11)),"1.2",StartSessionProblem.INVALID_WIDTH)
        failsAt(replaceWireless(start(),0,byteArrayOf(65,0,66)),"1.0",StartSessionProblem.INVALID_TEXT)
        failsAt(IapFields(start().ordered+listOf(7 to IapWire.fields(listOf(1 to byteArrayOf(0))))),"7.1",StartSessionProblem.INVALID_WIDTH)
    }
    @Test fun unsupportedSecurityNamesTheValueAndTheGap(){
        val failure=assertThrows(IllegalArgumentException::class.java){Bootstrap.wifiConfig(start(security=1),null,null){}}
        assertTrue(failure.message!!,failure.message!!.contains("security type 1"))
        assertTrue(failure.message!!,failure.message!!.contains("WEP"))
    }
    @Test fun outOfRangePasswordNamesItsLength(){
        val failure=assertThrows(IllegalArgumentException::class.java){Bootstrap.wifiConfig(start(password="short"),null,null){}}
        assertTrue(failure.message!!,failure.message!!.contains("5"))
    }
    @Test fun nonNumericAddressIsRefused(){
        assertThrows(IllegalArgumentException::class.java){Bootstrap.wifiConfig(start(address="not-an-address"),null,null){}}
    }
    @Test fun unchangedConfigurationKeepsTheNewSessionSnapshot(){
        val previous=Bootstrap.wifiConfig(start(),null,null){}
        val again=Bootstrap.wifiConfig(start(),null,previous){}
        assertNotSame(previous,again)
        assertTrue(previous.sameNetworkAs(again))
        assertTrue(again.startSessionReady)
    }
    @Test fun previousDiscoveryMustNotEraseNewStartOrCarryMetadataAndAuthentication(){
        val metadata=ReceiverMetadata("old-pair-id",null,1UL)
        val previous=Bootstrap.wifiConfig(start(),null,null){}
            .copy(startSession=null,authentication=BootstrapAuthentication(1,1,2,true))
            .resolved("192.168.1.2",7000,"receiver-id",null,metadata)
        val current=Bootstrap.wifiConfig(start(),null,previous){}
        assertTrue(previous.sameNetworkAs(current))
        assertEquals(io.github.iamr0s.carvex.carplay.core.SecurityEntry.IAP_START,current.endpointSource)
        assertTrue(current.startSessionReady)
        assertNull(current.endpoint!!.metadata)
        assertNull(current.authentication)
    }
    @Test fun changedSsidIsNotReused(){
        val previous=Bootstrap.wifiConfig(start(),null,null){}
        val changed=Bootstrap.wifiConfig(start(ssid="CAR-2"),null,previous){}
        assertNotSame(previous,changed)
        assertEquals("CAR-2",changed.ssid)
    }
    @Test fun conflictingPublicKeyIsNotReused(){
        val previous=Bootstrap.wifiConfig(start(publicKey="ab".repeat(32)),null,null){}
        val changed=Bootstrap.wifiConfig(start(publicKey="cd".repeat(32)),null,previous){}
        assertNotSame("a conflicting receiver key must force a fresh association",previous,changed)
    }
    @Test fun keyAppearingWhereThereWasNoneIsStillReusable(){
        val previous=Bootstrap.wifiConfig(start(),null,null){}
        val again=Bootstrap.wifiConfig(start(publicKey="cd".repeat(32)),null,previous){}
        assertNotSame("new identity evidence must be retained",previous,again)
        assertEquals(32,again.publicKey?.size)
    }
    @Test fun missingWirelessConfigurationDoesNotProveUsbReadiness(){
        assertNull(classifyCarPlayPath(null))
        assertEquals(CarPlayBootstrapPath.Wired,classifyCarPlayPath(null,usbTransportReady=true))
        val wireless=Bootstrap.wifiConfig(start(),null,null){}
        assertTrue(classifyCarPlayPath(wireless) is CarPlayBootstrapPath.Wireless)
    }
}
