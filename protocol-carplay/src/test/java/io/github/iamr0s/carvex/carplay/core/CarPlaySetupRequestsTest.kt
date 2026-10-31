package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class CarPlaySetupRequestsTest {
    private fun request(port:Int?=null,transportType:Int?=null,features:List<Any?>?=null,
        correlation:String?=null,encryption:CarPlaySetupRequests.Encryption?=null)=
        CarPlaySetupRequests.control("device-id","local-mac","phone-name","Android hardware","16",
            "current-session-uuid",port,encryption,correlation,transportType,features)

    @Test fun wirelessDefaultContainsOnlyImplementedCommonPrefixFields(){
        val actual=Plist.dictionary(Plist.encode(request()))
        assertEquals(setOf("deviceID","macAddress","name","model","osName","osVersion",
            "osBuildVersion","sessionUUID","sourceVersion","statsCollectionEnabled","updateSessionRequest"),actual.keys)
        assertEquals("device-id",actual["deviceID"]);assertEquals("local-mac",actual["macAddress"])
        assertEquals("phone-name",actual["name"]);assertEquals("Android hardware",actual["model"])
        assertEquals("Android",actual["osName"]);assertEquals("16",actual["osVersion"])
        assertEquals("320.17",actual["sourceVersion"]);assertEquals("21A329",actual["osBuildVersion"])
        assertEquals(false,actual["updateSessionRequest"]);assertEquals(false,actual["statsCollectionEnabled"])
    }

    @Test fun timingPortRequiresARealNonzeroProviderValueWithoutProtocolField(){
        for(port in listOf(null,0))assertFalse(request(port).containsKey("timingPort"))
        for(port in listOf(1,65535)){
            val actual=Plist.dictionary(Plist.encode(request(port)))
            assertEquals(port.toLong(),actual["timingPort"])
            assertFalse(actual.containsKey("timingProtocol"))
        }
        for(port in listOf(-1,65536))assertThrows(IllegalArgumentException::class.java){request(port)}
    }

    @Test fun transportTypeIsNotInferredFromSessionOrIapKind(){
        for(type in listOf(null,0,1,3,4,5))assertFalse(request(transportType=type).containsKey("keepAliveLowPower"))
        assertEquals(true,request(transportType=2)["keepAliveLowPower"])
    }

    @Test fun featuresAbsenceDiffersFromAnExplicitProviderEmptyArray(){
        assertFalse(request().containsKey("features"))
        assertEquals(emptyList<Any?>(),request(features=emptyList())["features"])
        val source=mutableListOf<Any?>("synthetic-feature")
        val fields=request(features=source)
        source.clear()
        assertEquals(listOf("synthetic-feature"),fields["features"])
    }

    @Test fun correlationIsNotInventedFromSessionUuid(){
        assertFalse(request().containsKey("sessionCorrelationUUID"))
        val fields=request(correlation="provider-correlation")
        assertEquals("provider-correlation",fields["sessionCorrelationUUID"])
        assertNotEquals(fields["sessionUUID"],fields["sessionCorrelationUUID"])
    }

    @Test fun encryptionFieldsAreAllAbsentOrSuppliedByTheExistingKeyHolder(){
        val absent=request()
        for(key in listOf("et","ekey","eiv"))assertFalse(absent.containsKey(key))
        val key=byteArrayOf(1,2,3);val iv=byteArrayOf(4,5,6)
        val fields=request(encryption=CarPlaySetupRequests.Encryption(16,key,iv))
        key.fill(0);iv.fill(0)
        val actual=Plist.dictionary(Plist.encode(fields))
        assertEquals(16L,actual["et"])
        assertArrayEquals(byteArrayOf(1,2,3),actual["ekey"] as ByteArray)
        assertArrayEquals(byteArrayOf(4,5,6),actual["eiv"] as ByteArray)
    }

    @Test fun knownOptionalInputsStillDoNotEnterGenericSessionBranches(){
        val fields=request(10001,2,listOf("synthetic"),"correlation")
        for(key in listOf("isRemoteControlOnly","isScreenMirroringSession","timingProtocol",
            "timingPeerInfo","PeerList","combinedGetInfoWithControlSetup","qualifier"))
            assertFalse(key,fields.containsKey(key))
    }
}
