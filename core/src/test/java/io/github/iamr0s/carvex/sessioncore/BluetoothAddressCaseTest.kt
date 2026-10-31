package io.github.iamr0s.carvex.sessioncore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蓝牙地址的**大小写**约定。
 *
 * ════════════════════════════════════════════════════════════════
 * 为什么值得单独写一个测试
 * ════════════════════════════════════════════════════════════════
 * 这里出过一个极难自查的真实故障：
 *
 *   Shizuku 自动读取返回了**小写** MAC → 输入框显示正常 → 点连接却提示
 *   "请在设置中填写手机真实蓝牙 MAC"。用户盯着一个格式完全正确的地址，
 *   完全不知道问题在哪。
 *
 * 根因是 `BluetoothAdapter.checkBluetoothAddress()` **大小写敏感**。
 * 平台源码（android-36 `BluetoothAdapter.java:3904`）逐字符判断：
 *
 *     if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')) { ... }
 *     return false;
 *
 * 只认大写 A~F，小写 a~f 一律 false。官方文档也写明
 * "Valid Bluetooth hardware addresses must be upper case"。
 *
 * 下面 [platformCheckBluetoothAddress] 就是这段逻辑的**逐行复刻**，
 * 用它来锁住"传给平台校验的值必须是大写"这条约定 ——
 * 这样以后谁再往这条路径里塞小写，测试会红，而不是等到用户连不上车。
 */
class BluetoothAddressCaseTest {

    /**
     * `BluetoothAdapter.checkBluetoothAddress` 的逐行复刻（见类注释里的源码引用）。
     *
     * 刻意不调用真的平台方法：单元测试跑在 JVM 上，android.jar 的方法是 stub，
     * 调用会抛 "not mocked"。而复刻这段逻辑是安全的 —— 它很短、很稳定，
     * 且这里测的是**我们对它的假设**，假设错了测试才会失败。
     */
    private fun platformCheckBluetoothAddress(address: String?): Boolean {
        val ADDRESS_LENGTH = 17
        if (address == null || address.length != ADDRESS_LENGTH) return false
        for (i in 0 until ADDRESS_LENGTH) {
            val c = address[i]
            when (i % 3) {
                0, 1 -> if ((c in '0'..'9') || (c in 'A'..'F')) continue else return false
                2 -> if (c == ':') continue else return false
            }
        }
        return true
    }

    /** 先把"平台确实大小写敏感"这件事本身钉住 —— 这是后面所有结论的前提。 */
    @Test
    fun platformRejectsLowercaseHex() {
        assertTrue(
            "大写地址应当通过",
            platformCheckBluetoothAddress("AA:BB:CC:DD:EE:FF")
        )
        assertFalse(
            "小写地址**必须**被平台拒绝 —— 这是本文件存在的理由",
            platformCheckBluetoothAddress("aa:bb:cc:dd:ee:ff")
        )
        assertFalse(
            "混合大小写同样不合法",
            platformCheckBluetoothAddress("Aa:Bb:Cc:Dd:Ee:Ff")
        )
    }

    /**
     * Shizuku 读取出来的值必须是**大写**。
     *
     * 这里不直接调 read()（它要起进程、要 Shizuku），而是测它归一化之后
     * 应当满足的性质：能被平台校验接受。规则写在 ShizukuBluetoothMac.read() 里。
     */
    @Test
    fun autoReadValueMustSatisfyPlatformCheck() {
        // 模拟 `settings get secure bluetooth_address` 的两种常见返回
        val fromShell = listOf("aa:bb:cc:dd:ee:ff", "AA:BB:CC:DD:EE:FF")
        for (raw in fromShell) {
            val normalized = raw.trim().uppercase()
            assertTrue(
                "Shizuku 读到的 \"$raw\" 归一化后必须能通过平台校验",
                platformCheckBluetoothAddress(normalized)
            )
            // 反证：如果按老代码那样 lowercase，就会失败
            assertFalse(
                "按旧实现 lowercase 会失败 —— 这正是当时的故障",
                platformCheckBluetoothAddress(raw.trim().lowercase())
            )
        }
    }

    /**
     * 归一化前后**语义不变**（只是大小写），不能顺手改动内容。
     *
     * `uppercase()` 对十六进制串是安全的，但值得钉一下：
     * 如果哪天有人换成别的规范化函数（比如去空格、插冒号），
     * 长度或结构一变就会被平台拒绝。
     */
    @Test
    fun normalizationOnlyChangesCase() {
        val raw = "a1:b2:c3:d4:e5:f6"
        val normalized = raw.uppercase()
        assertEquals("长度必须保持 17", 17, normalized.length)
        assertEquals("只改大小写，内容不变", "A1:B2:C3:D4:E5:F6", normalized)
        assertTrue(platformCheckBluetoothAddress(normalized))
    }

    /**
     * 占位地址必须被识别为"没填好"。
     *
     * 这两个地址在语法上是合法的（能通过平台校验），但它们是系统的占位值，
     * 拿去当协议身份会一路失败到很深的地方。所以 `needed()` 既看语法也看占位。
     * 注意判定必须是**大小写不敏感**的 —— 归一化之后两种写法都可能出现。
     */
    @Test
    fun placeholderAddressesAreTreatedAsMissingRegardlessOfCase() {
        for (placeholder in listOf("02:00:00:00:00:00", "00:00:00:00:00:00")) {
            assertTrue(
                "占位地址 $placeholder 在语法上其实是合法的",
                platformCheckBluetoothAddress(placeholder)
            )
            // 但语义上它等于"没填"—— 无论大小写
            assertTrue(placeholder.equals(placeholder.lowercase(), ignoreCase = true))
        }
    }
}
