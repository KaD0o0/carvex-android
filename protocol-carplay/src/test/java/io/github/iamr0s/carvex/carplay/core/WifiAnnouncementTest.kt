package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class WifiAnnouncementTest {
    private fun fields(ssid:String="synthetic-ssid",password:String="synthetic-password")=listOf(
        1 to IapWire.cstring(ssid),2 to IapWire.cstring(password),4 to byteArrayOf(6),3 to byteArrayOf(2))
    @Test fun fieldOrderRemainsIndependentAndLogsNeverContainCredentials() {
        val logs=mutableListOf<String>()
        val config=WifiAnnouncement.parse(IapFields(fields()),logs::add)
        assertEquals("synthetic-ssid",config.ssid)
        assertEquals("synthetic-password",config.password)
        assertEquals(2,config.security);assertEquals(6,config.channel)
        assertTrue(logs.any{it.contains("4:1")})
        assertFalse(logs.joinToString().contains("synthetic-ssid"))
        assertFalse(logs.joinToString().contains("synthetic-password"))
    }
    @Test fun integerWidthErrorsNameFieldAndLengthWithoutInterpretingOtherSchemas() {
        for(id in listOf(3,4)){
            val input=fields().map{if(it.first==id)id to byteArrayOf(0,2) else it}
            val error=assertThrows(WifiConfigurationException::class.java){WifiAnnouncement.parse(IapFields(input))}
            assertEquals(id,error.fieldId);assertEquals(2,error.actualBytes)
            assertEquals(WifiConfigurationProblem.INVALID_WIDTH,error.problem)
        }
    }
    @Test fun partialAnnouncementIsAcceptedButNetworkOnlyStillRequiresCredentials() {
        val partial=WifiAnnouncement.parse(IapFields(fields().filter{it.first!=1}))
        assertNull(partial.ssid)
        assertEquals("synthetic-password",partial.password)
        assertEquals(2,partial.security)
        assertThrows(WifiConfigurationException::class.java){partial.requireNetworkOnly()}
    }
    @Test fun duplicateFieldsUseTheLastValueAndMalformedKnownFieldStillFails() {
        val input=IapFields(fields()+listOf(1 to IapWire.cstring("replacement")))
        assertEquals("replacement",WifiAnnouncement.parse(input).ssid)
        val error=assertThrows(WifiConfigurationException::class.java){
            WifiAnnouncement.parse(IapFields(fields().map{if(it.first==2)2 to "secret-without-nul".toByteArray() else it}))
        }
        assertEquals(WifiConfigurationProblem.MISSING_NUL,error.problem)
        assertFalse(error.message!!.contains("secret-without-nul"))
        val empty=WifiAnnouncement.parse(IapFields(fields(ssid="")))
        assertEquals("",empty.ssid)
        assertThrows(WifiConfigurationException::class.java){empty.requireNetworkOnly()}
    }
    @Test fun bssidIsRetainedAsRawBytesAndOpenNetworkNeedsNoPassphrase() {
        val input=IapFields(listOf(0 to byteArrayOf(1,2,3),1 to IapWire.cstring("open"),3 to byteArrayOf(0)))
        val config=WifiAnnouncement.parse(input)
        assertArrayEquals(byteArrayOf(1,2,3),config.bssid)
        config.requireNetworkOnly()
    }
    @Test fun networkOnlyRestrictionsNameSecurityAndActualPasswordByteLength() {
        WifiAnnouncement("test","password1",3,6).requireNetworkOnly()
        val password=assertThrows(WifiConfigurationException::class.java){WifiAnnouncement("test","短",2,6).requireNetworkOnly()}
        assertEquals(WifiConfigurationProblem.INVALID_PASSPHRASE_LENGTH,password.problem)
        assertEquals(3,password.actualBytes)
        assertFalse(password.message!!.contains("短"))
        WifiAnnouncement("test","password1",2,6).requireNetworkOnly()
    }
    @Test fun stringTerminationIsCheckedBeforeLaterReplacement() {
        for(id in listOf(1,2))for(raw in listOf(byteArrayOf(),byteArrayOf(65,0,66))){
            val error=assertThrows(WifiConfigurationException::class.java){
                WifiAnnouncement.parse(IapFields(listOf(id to raw)+fields()))
            }
            assertEquals(id,error.fieldId)
            assertEquals(WifiConfigurationProblem.MISSING_NUL,error.problem)
        }
        val parsed=WifiAnnouncement.parse(IapFields(listOf(1 to byteArrayOf(65,0,66,0),2 to byteArrayOf(0))))
        assertEquals("A",parsed.ssid);assertEquals("",parsed.password)
    }
    @Test fun syntacticallyValidButUnusableSsidStillReachesPlatformBoundary() {
        for(ssid in listOf("", "x".repeat(33))){
            val parsed=WifiAnnouncement.parse(IapFields(fields(ssid=ssid)))
            assertEquals(ssid,parsed.ssid)
            assertEquals(WifiConfigurationProblem.INVALID_SSID,
                assertThrows(WifiConfigurationException::class.java){parsed.requireNetworkOnly()}.problem)
        }
    }
}
