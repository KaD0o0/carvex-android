package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class EaSessionPolicyTest {
    @Test fun acceptsWhateverNameThePeerAsksFor(){
        // 文档里的样本名当然要接受……
        assertEquals(EaSessionPolicy.Decision.Accept("com.hsae.datatransfer"),
            EaSessionPolicy.decide("com.hsae.datatransfer"))
        // ……但别的固件版本用别的名字时同样必须接受，否则"兼容所有"就落空了。
        assertEquals(EaSessionPolicy.Decision.Accept("com.example.other"),
            EaSessionPolicy.decide("com.example.other"))
        assertEquals(EaSessionPolicy.Decision.Accept("com.apple.accessory"),
            EaSessionPolicy.decide("  com.apple.accessory  "))
    }

    @Test fun rejectsOnlyUnrecordableNames(){
        assertTrue(EaSessionPolicy.decide(null) is EaSessionPolicy.Decision.Reject)
        assertTrue(EaSessionPolicy.decide("   ") is EaSessionPolicy.Decision.Reject)
        assertTrue(EaSessionPolicy.decide("a".repeat(129)) is EaSessionPolicy.Decision.Reject)
    }
}
