package io.github.iamr0s.carvex.ui

import io.github.iamr0s.carvex.sessioncore.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住日志分组与解析。
 *
 * 这些规则都出过（或极易出）"编译通过、只有肉眼才发现"的故障：
 *
 *  1. **边界关键字写错**。曾经用界面文案（"开始连接"）当边界，
 *     而日志里根本没有那句话 —— 结果是**全文永远只有一组**，
 *     分组功能静默失效，界面看上去"正常"，只是折叠头只有一个。
 *
 *  2. **时间戳解析错位**。真实格式是 `ISO-8601 [id] message`，
 *     不含 LEVEL 列。若按 "HH:mm:ss LEVEL message" 切，
 *     正文会被当成级别，整段日志的列宽全乱。
 *
 *  3. **边界行归错组**。"stage HOST" 是这一段的**开头**，
 *     归到上一组会让每次连接都挂在它前一次尝试的末尾。
 */
class LogGroupingTest {

    /**
     * 造一条与 BridgeStore.log 格式一致的行。
     *
     * 真实格式是 `Instant.now().toString() + " [" + id + "] " + message`，
     * 而有毫秒时 Instant.toString() 产出的是
     * `2024-05-01T12:34:56.789Z`（**24** 字符，不是 19）。
     * 这个长度差正是解析出过错的地方，所以默认值就用带毫秒的形式。
     */
    private fun line(
        iso: String = "2024-05-01T12:34:56.789Z",
        id: String = "abcd1234",
        message: String,
    ) = "$iso [$id] $message"

    @Test
    fun emptyInputProducesNoGroups() {
        assertTrue(groupLogs(emptyList()).isEmpty())
    }

    @Test
    fun linesWithoutBoundaryFormOneGroup() {
        // 没有 "stage HOST" 时不能产生 0 组 —— 否则界面显示"暂无日志"，
        // 但日志明明有内容，用户会以为日志功能坏了。
        val groups = groupLogs(
            listOf(
                line(message = "日志已导出"),
                line(message = "设置已保存"),
            )
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].lines.size)
    }

    @Test
    fun eachStageHostStartsANewGroup() {
        val groups = groupLogs(
            listOf(
                line(message = "stage HOST"),
                line(message = "stage BLUETOOTH"),
                line(message = "stage DETECT"),
                line(message = "stage HOST"),
                line(message = "stage WIFI"),
            )
        )
        assertEquals("两次尝试应分成两组", 2, groups.size)
        assertEquals(3, groups[0].lines.size)
        assertEquals(2, groups[1].lines.size)
    }

    @Test
    fun boundaryLineBelongsToTheNewGroup() {
        // 关键：边界行是这一段的**开头**，必须落在新组里。
        // 归错的话，每次连接都会挂在它前一次尝试的末尾。
        val groups = groupLogs(
            listOf(
                line(message = "上一轮的尾声"),
                line(message = "stage HOST"),
            )
        )
        assertEquals(2, groups.size)
        assertEquals("上一轮的尾声", groups[0].lines.single().message)
        assertEquals("stage HOST", groups[1].lines.single().message)
    }

    @Test
    fun groupIdsAreStableAndUnique() {
        // id 是 LazyColumn 的 key，也是"哪些组被展开"的键。
        // 重复或随重组变化会让展开状态串台。
        val groups = groupLogs(
            listOf(
                line(message = "stage HOST"),
                line(message = "stage HOST"),
                line(message = "stage HOST"),
            )
        )
        val ids = groups.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(listOf("g0", "g1", "g2"), ids)
    }

    // ── 单行解析 ────────────────────────────────────────────────────

    @Test
    fun parsesIsoTimestampAndDropsSessionId() {
        val parsed = parseLogLine(line(message = "stage HOST"))
        assertEquals("12:34:56", parsed.time)
        assertEquals("stage HOST", parsed.message)
        // 本项目的日志没有级别列，不编造一个。
        assertEquals("", parsed.level)
    }

    @Test
    fun millisecondTimestampLeavesNoResidueInTheMessage() {
        // ════════════════════════════════════════════════════════════
        // 这条钉住一个真实缺陷
        // ════════════════════════════════════════════════════════════
        // Instant.toString() 带毫秒时是 "…56.789Z"（24 字符），不带时是
        // "…56Z"（20 字符）。曾经按固定 substring(19) 截时间戳，
        // 于是 ".789Z [abcd1234] " 全部留在正文里 ——
        // 界面上每一行正文前面都挂着一截时间戳残渣，
        // 而且因为只在"有毫秒"时出现，很容易被当成偶发。
        //
        // 判据：正文里不能出现 ISO 时间戳的任何残片。
        val parsed = parseLogLine(line(message = "stage HOST"))
        assertFalse(
            "正文里不应残留时间戳：'${parsed.message}'",
            parsed.message.contains(".789") || parsed.message.contains("Z"),
        )
        assertFalse(
            "正文里不应残留会话 id：'${parsed.message}'",
            parsed.message.contains("abcd1234"),
        )
    }

    @Test
    fun timestampWithoutMillisAlsoParses() {
        // 秒为整时 Instant.toString() 会省略毫秒（20 字符）——
        // 两种形状都必须能解析，否则日志会"有时正常有时错位"。
        val parsed = parseLogLine(line(iso = "2024-05-01T12:34:56Z", message = "stage HOST"))
        assertEquals("12:34:56", parsed.time)
        assertEquals("stage HOST", parsed.message)
    }

    @Test
    fun unparseableLineIsKeptWholeAsMessage() {
        // 解析不出来时整行当正文，而不是丢字段后错位。
        // 旧版本或手工写入的行会走到这条路。
        val raw = "没有任何时间戳的一行"
        val parsed = parseLogLine(raw)
        assertEquals("", parsed.time)
        assertEquals(raw, parsed.message)
    }

    @Test
    fun missingSessionIdStillParses() {
        val parsed = parseLogLine("2024-05-01T12:34:56.789Z 直接是正文")
        assertEquals("12:34:56", parsed.time)
        assertEquals("直接是正文", parsed.message)
    }

    // ── 折叠头文案 ──────────────────────────────────────────────────

    @Test
    fun groupTitleUsesChineseStageNameNotProtocolName() {
        // 用户不该在折叠头上看到 "HOST" 这种协议内部名字。
        val groups = groupLogs(listOf(line(message = "stage HOST")))
        assertEquals(Stage.HOST.title, groups.single().title)
        assertFalse(
            "折叠头不应直接暴露协议阶段名",
            groups.single().title.contains("HOST")
        )
    }

    @Test
    fun unknownStageFallsBackToRawNameInsteadOfHidingIt() {
        // 查不到的阶段名原样显示 —— 隐藏信息比显示一个内部名更糟，
        // 至少排障时还能看出发生了什么。
        val groups = groupLogs(listOf(line(message = "stage NOT_A_REAL_STAGE")))
        assertEquals("NOT_A_REAL_STAGE", groups.single().title)
    }

    @Test
    fun plainTextKeepsEveryLineForCopyAndExport() {
        // 复制/导出走 plainText()，不能因为分组而丢行。
        val groups = groupLogs(
            listOf(
                line(message = "stage HOST"),
                line(message = "stage WIFI"),
            )
        )
        val text = groups.joinToString("\n\n") { it.plainText() }
        groups.forEach { group ->
            group.lines.forEach { assertTrue(text.contains(it.message)) }
        }
    }
}
