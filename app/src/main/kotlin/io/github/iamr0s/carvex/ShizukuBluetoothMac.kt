package io.github.iamr0s.carvex

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** 没填手机蓝牙 MAC 时的兜底：用 Shizuku 以 shell 身份读系统里那条
 *  `Settings.Secure.bluetooth_address`。普通应用从 Android 6 起读不到它（要 BLUETOOTH_PRIVILEGED），
 *  只能借 shell 的权限去问。
 *
 * 走 `settings get secure bluetooth_address` 而不是直接拿 content 服务的 binder：后者要
 *  `IContentProvider` 这个隐藏接口，而 shell 命令读的就是同一条设置，且不依赖任何隐藏 API。 */
object ShizukuBluetoothMac {
    /** Shizuku 授权结果的 requestCode，和 [Shizuku.addRequestPermissionResultListener] 对齐。 */
    const val REQUEST_CODE = 0x5348

    private val MAC = Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}")

    /** Shizuku 服务在跑，且版本够用（newProcess 要 v11 的 API）。 */
    fun available(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.getVersion() >= 11
    }.getOrDefault(false)

    fun granted(): Boolean = runCatching {
        available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 拉起 Shizuku 的授权界面；结果从权限监听器回来，这里不等。 */
    fun request(): Boolean = runCatching {
        if (!available() || granted()) return@runCatching false
        Shizuku.requestPermission(REQUEST_CODE)
        true
    }.getOrDefault(false)

    /**
     * 读本机蓝牙 MAC；没授权、读不到或格式不对都返回 null。会起进程，必须放在工作线程。
     *
     * ════════════════════════════════════════════════════════════════
     * ⚠️ 返回值必须是**大写** —— 这里修的是一个真实的连接故障
     * ════════════════════════════════════════════════════════════════
     * 症状：设置页点 Shizuku 按钮，MAC 明明填进输入框了，回首页点连接
     *       却提示"请在设置中填写手机真实蓝牙 MAC"。
     *
     * 原因：这里原来写的是 `.lowercase()`（把小写当"规范化"），
     *       而 `BluetoothAdapter.checkBluetoothAddress()` 是**大小写敏感**的。
     *       平台源码（android-36 BluetoothAdapter.java:3904）逐字符判断：
     *
     *           if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')) { ... }
     *           return false;
     *
     *       它只认 **大写** A~F，小写 a~f 直接判为 false。
     *       官方文档也写明："Valid Bluetooth hardware addresses must be upper case"。
     *
     *       所以小写 MAC 会在 SessionController 的身份校验那一关被拒，
     *       而输入框显示的却是一个"看起来完全正确"的地址 —— 极难自查。
     *
     * 为什么之前没暴露：手动输入的路径上有 normalizeMac()（会 uppercase），
     *       只有 Shizuku 自动读取这条路径漏了，而它恰好是"用户不想手填"时走的路径。
     *
     * 注意 `MAC` 这个正则本身用的是 `[0-9a-fA-F]`（两种大小写都收），
     * 它只负责"格式对不对"，不负责大小写规范化 —— 规范化在下面这行做。
     */
    @Suppress("DEPRECATION")
    fun read(): String? {
        if (!granted()) return null
        return runCatching {
            val process = Shizuku.newProcess(
                arrayOf("sh", "-c", "settings get secure bluetooth_address"), null, null
            )
            val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
            runCatching { process.waitFor() }
            // uppercase，不是 lowercase：见上面的说明。
            text.takeIf { MAC.matches(it) }?.uppercase()
        }.getOrNull()
    }

    /** 本机 MAC 是否还没填好（空、非法，或系统占位地址）。 */
    fun needed(phoneMac: String): Boolean =
        !android.bluetooth.BluetoothAdapter.checkBluetoothAddress(phoneMac) ||
            phoneMac.equals("02:00:00:00:00:00", true) || phoneMac.equals("00:00:00:00:00:00", true)
}
