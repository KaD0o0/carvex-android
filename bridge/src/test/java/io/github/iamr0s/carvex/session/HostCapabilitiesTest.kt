package io.github.iamr0s.carvex.session

import io.github.iamr0s.carvex.sessioncore.HostCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 能力声明的默认值就是"未知=全能力"，这样未声明能力的宿主不会因为新增门槛而退化。 */
class HostCapabilitiesTest {
    @Test fun defaultsKeepTheOldBehaviour(){
        val caps=HostCapabilities()
        assertTrue(caps.touch)
        assertTrue(caps.keys)
        assertTrue(caps.microphone)
        assertEquals(1,caps.displayCount)
        assertNull(caps.maxFps)
    }

    @Test fun fpsLimitIsOptionalAndComposable(){
        val limited=HostCapabilities(maxFps=30)
        assertEquals(30,limited.maxFps)
        // 编排层用 minOf 夹取，宿主上限不得被协议请求抬高。
        assertEquals(30,minOf(60,limited.maxFps!!))
        assertEquals(24,minOf(24,limited.maxFps!!))
    }
}
