package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.*
import org.junit.Test
class WriteDeadlineTest {
    @Test fun idleNeverExpiresAndActualWriteExpiresOnce(){
        val d=WriteDeadline(100)
        assertFalse(d.expire(1000))
        d.begin(1000)
        assertFalse(d.expire(1099));assertTrue(d.expire(1100));assertFalse(d.expire(1101))
    }
    @Test fun oldCompletionCannotCancelNextWrite(){
        val d=WriteDeadline(100)
        val old=d.begin(0);d.complete(old)
        val next=d.begin(50);d.complete(old)
        assertTrue(d.expire(150));d.complete(next)
        assertFalse(d.expire(1000))
    }
    @Test fun completedWriteAllowsUnlimitedIdle(){
        val d=WriteDeadline(100)
        val ticket=d.begin(0);d.complete(ticket)
        assertFalse(d.expire(1000000))
    }
}
