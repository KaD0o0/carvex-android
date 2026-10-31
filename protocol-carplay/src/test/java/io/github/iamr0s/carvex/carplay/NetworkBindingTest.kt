package io.github.iamr0s.carvex.carplay

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketException
import io.github.iamr0s.carvex.sessioncore.TransportException
import io.github.iamr0s.carvex.sessioncore.TransportErrorCategory

/** VPN permission refusals allow a source-bound attempt; unrelated bind errors still fail. */
class NetworkBindingTest {
    private fun refusal()=SocketException("bind() failed: EPERM (Operation not permitted)")

    @Test fun aRefusedPinIsReportedWithoutEndingTheConnection(){
        val refusal=refusal()
        var reported:SocketException?=null
        assertFalse(Bootstrap.pin(true,{reported=it}){throw refusal})
        assertSame(refusal,reported)
    }
    @Test fun accessDeniedHasTheSameNonfatalPermissionPolicy(){
        val denied=SocketException("bind() failed: EACCES (Permission denied)")
        var reports=0
        assertFalse(Bootstrap.pin(true,{assertSame(denied,it);reports++}){throw denied})
        assertEquals(1,reports)
    }
    @Test fun anUnrelatedSocketFailureIsAlsoTyped(){
        val error=SocketException("Network is unreachable")
        val failure=assertThrows(TransportException::class.java){Bootstrap.pin(true,{}){throw error}}
        assertEquals(TransportErrorCategory.INTERFACE_BIND_FAILED,failure.category)
        assertSame(error,failure.cause)
    }

    @Test fun aSuccessfulPinIsReportedAsBound(){
        assertTrue(Bootstrap.pin(true,{}){})
    }

    @Test fun switchingBindingOffCannotSilentlyUseADifferentNetwork(){
        var touched=false
        val failure=assertThrows(TransportException::class.java){Bootstrap.pin(false,{}){touched=true}}
        assertEquals(TransportErrorCategory.INTERFACE_BIND_FAILED,failure.category)
        assertFalse(touched)
    }
}
