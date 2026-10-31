package io.github.iamr0s.carvex.ui

import io.github.iamr0s.carvex.sessioncore.BondedDevices
import io.github.iamr0s.carvex.sessioncore.DeviceHistory
import io.github.iamr0s.carvex.sessioncore.Stage
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Pure presentation contracts shared by the UI and its JVM tests. */
sealed interface Link {
    val stoppable: Boolean

    data object Idle : Link {
        override val stoppable: Boolean = false
    }

    data class Connecting(
        val deviceId: String,
        val methodLabel: String,
        val detail: String,
    ) : Link {
        override val stoppable: Boolean = true
    }

    data class Connected(
        val deviceId: String,
        val methodLabel: String,
        val since: Long,
        val latencyMs: Long = -1L,
    ) : Link {
        override val stoppable: Boolean = true
    }

    companion object {
        fun of(stage: Stage, target: String, sessionId: String, since: Long, detail: String): Link {
            if (sessionId.isBlank() || stage == Stage.FAILED || stage == Stage.STOPPING) return Idle
            val device = target
            return if (stage.connected) Connected(device, stage.title, since)
                else Connecting(device, stage.title, detail)
        }
    }
}

fun latencyLabel(latencyMs: Long): String? = latencyMs.takeIf { it >= 0 }?.let { "$it ms" }

fun uptimeLabel(since: Long, now: Long): String {
    val seconds = ((now - since).coerceAtLeast(0L)) / 1000L
    return "${seconds / 60} 分 ${seconds % 60} 秒"
}

data class DeviceList(
    val history: List<DeviceHistory.Entry>,
    val paired: List<BondedDevices.Device>,
) {
    val total: Int get() = history.size + paired.size
    val isEmpty: Boolean get() = total == 0
}

object DeviceListModel {
    fun build(
        history: List<DeviceHistory.Entry>,
        paired: List<BondedDevices.Device>,
    ): DeviceList {
        val latest = history
            .groupBy { it.address.uppercase() }
            .values
            .map { entries -> entries.maxBy { it.lastConnectedAt } }
            .sortedByDescending { it.lastConnectedAt }
        val known = latest.mapTo(hashSetOf()) { it.address.uppercase() }
        return DeviceList(latest, paired.filterNot { it.address.uppercase() in known })
    }
}

data class LogLine(val time: String, val level: String, val message: String) {
    override fun toString(): String = listOf(time, level, message).filter { it.isNotBlank() }.joinToString(" ")
}

data class LogGroup(val id: String, val title: String, val lines: List<LogLine>) {
    fun plainText(): String = lines.joinToString("\n") { line ->
        listOf(line.time, line.level, line.message).filter { it.isNotBlank() }.joinToString(" ")
    }
}

private val logTimestamp = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)

fun parseLogLine(raw: String): LogLine {
    val match = Regex("^(\\d{4}-\\d{2}-\\d{2}T[^ ]+Z)\\s+(?:\\[([^]]+)]\\s*)?(.*)$").find(raw)
        ?: return LogLine("", "", raw)
    val instant = runCatching { Instant.parse(match.groupValues[1]) }.getOrNull()
    return LogLine(
        time = instant?.let(logTimestamp::format).orEmpty(),
        level = "",
        message = match.groupValues[3],
    )
}

fun groupLogs(raw: List<String>): List<LogGroup> {
    if (raw.isEmpty()) return emptyList()
    val groups = mutableListOf<MutableList<LogLine>>()
    for (line in raw.map(::parseLogLine)) {
        val startsAttempt = line.message.startsWith("stage HOST")
        if (startsAttempt || groups.isEmpty()) groups.add(mutableListOf())
        groups.last() += line
    }
    return groups.mapIndexed { index, lines ->
        val stage = lines.firstOrNull()?.message?.removePrefix("stage ").orEmpty()
        val title = runCatching { Stage.valueOf(stage).title }.getOrDefault(stage)
        LogGroup("g$index", title, lines)
    }
}

enum class Tab { DEVICES, SETTINGS }

enum class Detail(val tab: Tab) {
    Manage(Tab.DEVICES),
    Logs(Tab.SETTINGS),
}
