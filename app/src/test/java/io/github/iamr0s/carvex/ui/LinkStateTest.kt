package io.github.iamr0s.carvex.ui

import io.github.iamr0s.carvex.sessioncore.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住 [Link.of] 的三态映射。
 *
 * 为什么值得写测试：这套映射出过（也很容易再出）一类**只有肉眼才发现**的故障 ——
 * 界面各处各自 `when (stage)` 判断"连上没有"，于是主卡说"已连接"、
 * 设备列表说"连接中"，同一屏自相矛盾；或者 RETRY / STOPPING 这类
 * 边角阶段落在 `else` 里，被当成"未连接"，界面在重试期间闪回空态。
 *
 * 现在映射只写一处，这里把每一条边界钉死。
 */
class LinkStateTest {

    private fun of(
        stage: Stage,
        target: String = "AA:BB:CC:DD:EE:FF",
        sessionId: String = "abcd1234",
        since: Long = 1000L,
        detail: String = "",
    ) = Link.of(stage, target, sessionId, since, detail)

    // ── 没有会话 ────────────────────────────────────────────────────

    @Test
    fun noSessionIdIsIdle() {
        // sessionId 为空 = 还没有会话。这是判"活"的**必要**条件：
        // target 在断开后仍会保留上一次的选择，只看 target 会把"断开"
        // 误判成"还连着"。
        assertTrue(of(Stage.IDLE, sessionId = "") is Link.Idle)
        assertTrue(of(Stage.STREAMING, sessionId = "") is Link.Idle)
    }

    @Test
    fun failedAndStoppingAreIdle() {
        // 失败与正在停止都不是"连上了"，也不是"在连"。
        assertTrue(of(Stage.FAILED) is Link.Idle)
        assertTrue(of(Stage.STOPPING) is Link.Idle)
    }

    @Test
    fun idleStageWithoutSessionIsIdle() {
        // 没有会话 id 就是"未连接"，无论 stage 是什么。
        assertTrue(of(Stage.IDLE, sessionId = "") is Link.Idle)
    }

    @Test
    fun idleStageWithSessionIsConnecting() {
        // 有会话但 stage 还是 IDLE：用户在点击后、第一个阶段推进前的瞬间。
        // 必须是 Connecting —— 若归到 Idle，界面会在点击后闪回"未连接"。
        assertTrue(of(Stage.IDLE) is Link.Connecting)
    }

    // ── 正在连接 ────────────────────────────────────────────────────

    @Test
    fun handshakeStagesAreConnecting() {
        // HOST / BLUETOOTH / DETECT / AUTH 都是"还在握手的路上"，
        // 对用户来说是"连接中"，不是"已连接"。
        // 曾经用排除法（非 IDLE/FAILED/STOPPING 就算连上）推导，
        // 结果拨号阶段就显示"已连接 · 视频发送中" —— 这条测试钉住修正。
        assertTrue("HOST 应为连接中", of(Stage.HOST) is Link.Connecting)
        assertTrue("BLUETOOTH 应为连接中", of(Stage.BLUETOOTH) is Link.Connecting)
        assertTrue("DETECT 应为连接中", of(Stage.DETECT) is Link.Connecting)
        assertTrue("AUTH 应为连接中", of(Stage.AUTH) is Link.Connecting)
    }

    @Test
    fun retryIsConnectingNotIdle() {
        // 重试期间会话仍然活着，界面必须继续说"连接中"。
        assertTrue(of(Stage.RETRY) is Link.Connecting)
    }

    @Test
    fun connectingCarriesDeviceAndDetail() {
        val link = of(Stage.BLUETOOTH, detail = "正在配对") as Link.Connecting
        assertEquals("AA:BB:CC:DD:EE:FF", link.deviceId)
        assertEquals(Stage.BLUETOOTH.title, link.methodLabel)
        assertEquals("正在配对", link.detail)
    }

    @Test
    fun connectingSurvivesAMissingTarget() {
        // 有会话但没有目标地址时，状态仍是"连接中" ——
        // target 只影响能否显示设备名，不该让状态退回"未连接"。
        assertTrue(of(Stage.BLUETOOTH, target = "") is Link.Connecting)
    }

    // ── 已连接 ──────────────────────────────────────────────────────

    @Test
    fun streamingIsConnected() {
        assertTrue(of(Stage.STREAMING) is Link.Connected)
        assertTrue(of(Stage.WAIT_FRAME) is Link.Connected)
    }

    @Test
    fun linkEstablishedPhasesCountAsConnected() {
        // 画面协商才是已连接边界，Wi-Fi 与安全配对仍显示各自的连接进度。
        assertTrue(of(Stage.WIFI) is Link.Connecting)
        assertTrue(of(Stage.PAIR) is Link.Connecting)
        assertTrue(of(Stage.SETUP) is Link.Connected)
    }

    @Test
    fun connectedCarriesSinceAndUnknownLatency() {
        val link = of(Stage.STREAMING, since = 4242L) as Link.Connected
        assertEquals(4242L, link.since)
        // 没有真实测量时是 -1，界面据此**不显示**时延那一行。
        assertEquals(-1L, link.latencyMs)
    }

    @Test
    fun latencyIsHiddenWhenUnmeasured() {
        assertEquals(null, latencyLabel(-1L))
        assertEquals("42 ms", latencyLabel(42L))
    }

    // ── 阶段文案 ────────────────────────────────────────────────────

    // ── 阶段文案 ────────────────────────────────────────────────────

    @Test
    fun everyStageHasATitleFromTheProtocolLayer() {
        // 界面不再自己编阶段文案，一律取 Stage.title —— 两处文案迟早会不一致。
        Stage.entries.forEach { stage ->
            assertTrue(
                "${stage.name} 的 title 不应为空",
                stage.title.isNotBlank()
            )
        }
    }

    // ── 能不能停止 ──────────────────────────────────────────────────

    @Test
    fun connectingIsStoppable() {
        // ════════════════════════════════════════════════════════════
        // 这条钉住一个真实缺陷
        // ════════════════════════════════════════════════════════════
        // 停止按钮最初判的是 `link is Link.Connected`，于是从点下"连接"
        // 到真正连上的那几十秒里，界面上没有任何退出手段 ——
        // 用户想改主意、或者选错了车机，只能干等它失败或连上。
        val link = of(Stage.BLUETOOTH)
        assertTrue("连接中必须能停止", link is Link.Connecting)
        assertTrue("连接中必须能停止", link.stoppable)
    }

    @Test
    fun connectedIsStoppable() {
        assertTrue(of(Stage.STREAMING).stoppable)
    }

    @Test
    fun idleIsNotStoppable() {
        // 没有会话就没有东西可以停止 —— 未连接时不该摆一个停止按钮。
        assertFalse(of(Stage.IDLE, sessionId = "").stoppable)
    }

    @Test
    fun everyNonIdleStateIsStoppable() {
        // 穷尽检查：只要界面上显示"在连接"或"已连接"，就必须给得出退出手段。
        // 以后新增阶段时，这条会挡住"新阶段忘了考虑停止按钮"的情况。
        Stage.entries.forEach { stage ->
            val link = of(stage)
            if (link !is Link.Idle) {
                assertTrue(
                    "$stage 映射到 $link，非 Idle 状态必须可以停止",
                    link.stoppable,
                )
            }
        }
    }
}

/**
 * 锁住 [uptimeLabel] 的格式。
 *
 * 它是"已连接多久"的唯一来源，出错的形态是负数或 "-1 分 -1 秒" ——
 * 那通常意味着 since 被设成了未来时间（例如会话 id 变化时忘记重置）。
 */
class UptimeLabelTest {

    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("0 分 0 秒", uptimeLabel(since = 0L, now = 0L))
        assertEquals("3 分 12 秒", uptimeLabel(since = 0L, now = 192_000L))
        assertEquals("1 分 0 秒", uptimeLabel(since = 0L, now = 60_000L))
    }

    @Test
    fun clampsFutureSinceInsteadOfGoingNegative() {
        // since 在未来（时钟回拨或忘记重置）时，宁可显示 0 秒，
        // 也不要显示 "-1 分 -30 秒" —— 后者会让人以为功能坏了。
        assertEquals("0 分 0 秒", uptimeLabel(since = 10_000L, now = 0L))
    }
}
