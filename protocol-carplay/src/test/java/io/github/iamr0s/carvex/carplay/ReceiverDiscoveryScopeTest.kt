package io.github.iamr0s.carvex.carplay

import io.github.iamr0s.carvex.sessioncore.*
import org.junit.Assert.*
import org.junit.Test

class ReceiverDiscoveryScopeTest {
    @Test fun unsupportedGlobalDiscoveryFailsSpecificallyRatherThanTimingOut(){
        for(api in 29..32){
            val error=assertThrows(TransportException::class.java){ReceiverDiscoveryScope.requireSupported(api,false)}
            assertEquals(TransportErrorCategory.PLATFORM_UNSUPPORTED,error.category)
            assertTrue(error.message!!.contains("43/01"))
            ReceiverDiscoveryScope.requireSupported(api,true)
        }
    }
    @Test fun networkScopedDiscoveryRemainsAvailableOnNewSystems(){
        for(api in 33..36){ReceiverDiscoveryScope.requireSupported(api,false);ReceiverDiscoveryScope.requireSupported(api,true)}
    }
}
