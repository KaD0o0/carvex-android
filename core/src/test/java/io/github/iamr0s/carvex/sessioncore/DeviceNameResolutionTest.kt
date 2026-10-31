package io.github.iamr0s.carvex.sessioncore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「上报给车机的名称」的取值规则。
 *
 * 这里只测不依赖 Context 的部分（BRIDGE 的 resolvedDeviceName 缓存与回退链）——
 * `localDeviceName` 要读 ContentResolver，属于设备行为，留给实机验证。
 */
class DeviceNameResolutionTest {

    @Test fun blankDeviceNameFallsBackToResolvedLocalName() {
        val previous = Options.resolvedDeviceName
        try {
            Options.resolvedDeviceName = "Xiaomi 17"
            val o = Options(deviceName = "")
            assertEquals("Xiaomi 17", o.carPlayName)
        } finally {
            Options.resolvedDeviceName = previous
        }
    }

    @Test fun explicitDeviceNameWinsOverResolvedLocalName() {
        val previous = Options.resolvedDeviceName
        try {
            Options.resolvedDeviceName = "Xiaomi 17"
            // 用户显式填了就必须用填写值 —— 自动探测只是默认，不能覆盖用户的输入。
            assertEquals("我的车", Options(deviceName = "我的车").carPlayName)
        } finally {
            Options.resolvedDeviceName = previous
        }
    }

    @Test fun whitespaceOnlyDeviceNameIsTreatedAsBlank() {
        val previous = Options.resolvedDeviceName
        try {
            Options.resolvedDeviceName = "Xiaomi 17"
            assertEquals("Xiaomi 17", Options(deviceName = "   ").carPlayName)
        } finally {
            Options.resolvedDeviceName = previous
        }
    }

    @Test fun constantIsTheLastResortWhenNothingElseResolves() {
        val previous = Options.resolvedDeviceName
        try {
            // 连本机名字都取不到时，仍要给出一个可用的名字，而不是空串
            // （空串会被对端当成"没有设备名"，iAP2/Bonjour 都可能拒绝）。
            Options.resolvedDeviceName = ""
            assertEquals(Options.DEVICE_NAME_DEFAULT, Options(deviceName = "").carPlayName)
            assertTrue(Options.DEVICE_NAME_DEFAULT.isNotBlank())
        } finally {
            Options.resolvedDeviceName = previous
        }
    }

    @Test fun macRegexAcceptsCanonicalAndRejectsShortForms() {
        assertTrue(Options.MAC_REGEX.matches("AA:BB:CC:DD:EE:FF"))
        assertTrue(Options.MAC_REGEX.matches("00:11:22:33:44:55"))
        // 常见错法：少一段、多一段、用横杠、非十六进制
        assertEquals(false, Options.MAC_REGEX.matches("AA:BB:CC:DD:EE"))
        assertEquals(false, Options.MAC_REGEX.matches("AA:BB:CC:DD:EE:FF:00"))
        assertEquals(false, Options.MAC_REGEX.matches("AA-BB-CC-DD-EE-FF"))
        assertEquals(false, Options.MAC_REGEX.matches("GG:BB:CC:DD:EE:FF"))
    }
}
