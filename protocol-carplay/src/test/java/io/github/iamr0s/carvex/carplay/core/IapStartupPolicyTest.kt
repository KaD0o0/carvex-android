package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class IapStartupPolicyTest {
    @Test fun everyWireControlVersionUsesTheSameExplicitSupportedSchemaSet(){
        for(version in 0..255){
            val expected=when(version){
                1->IapStartupPolicy.Schema.CSM_CONTROL_V1
                2->IapStartupPolicy.Schema.CSM_CONTROL_V2
                else->null
            }
            assertEquals("control version $version",expected,
                IapStartupPolicy.selectSchema(IapControlSessionKind.CARPLAY,version))
        }
    }

    @Test fun supportedControlSchemasUseNegotiatedSessionVersion(){
        assertEquals(IapStartupPolicy.Schema.CSM_CONTROL_V1,IapStartupPolicy.selectSchema(IapControlSessionKind.CARPLAY,1))
        assertEquals(IapStartupPolicy.Schema.CSM_CONTROL_V2,IapStartupPolicy.selectSchema(IapControlSessionKind.CARPLAY,2))
        assertEquals(IapStartupPolicy.Order.AUTH_FIRST,IapStartupPolicy.u2iwStart(IapControlSessionKind.CARPLAY,1))
        assertEquals(IapStartupPolicy.Order.IDENT_FIRST,IapStartupPolicy.u2iwStart(IapControlSessionKind.CARPLAY,2))
        listOf(null,0,3,255).forEach{assertNull(IapStartupPolicy.u2iwStart(IapControlSessionKind.CARPLAY,it))}
    }

    @Test fun ipodBootstrapHasItsOwnOrderInsteadOfCarplayVersionTwoRule(){
        listOf(null,1,2,3).forEach{
            assertEquals(IapStartupPolicy.Order.AUTH_FIRST,IapStartupPolicy.u2iwStart(IapControlSessionKind.IPOD_BOOTSTRAP,it))
        }
        assertNull(IapStartupPolicy.u2iwStart(IapControlSessionKind.OTHER,1))
    }

    @Test fun unknownVersionsNeverGuessATransactionOrder(){
        listOf(null,0,3,255).forEach{
            assertNull(IapStartupPolicy.selectSchema(IapControlSessionKind.CARPLAY,it))
            assertThrows(IllegalStateException::class.java){IapStartupPolicy.candidateStart(IapControlSessionKind.CARPLAY,it)}
        }
        assertNull(IapStartupPolicy.selectSchema(IapControlSessionKind.OTHER,1))
    }
}
