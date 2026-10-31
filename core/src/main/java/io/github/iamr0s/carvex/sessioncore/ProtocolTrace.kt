package io.github.iamr0s.carvex.sessioncore

/** Stable, redaction-safe diagnostic events; this is a local log format, not a wire message. */
object ProtocolTrace {
    fun event(
        phase: String,
        transport: String,
        direction: String,
        transaction: String,
        outcome: String,
        detail: String? = null,
    ): String {
        fun token(value: String): String = value.trim()
            .replace(Regex("[^A-Za-z0-9_.:/-]"), "_")
            .ifBlank { "-" }
        val base = "trace phase=${token(phase)} transport=${token(transport)} direction=${token(direction)} tx=${token(transaction)} outcome=${token(outcome)}"
        return detail?.trim()?.takeIf { it.isNotEmpty() }?.let { "$base detail=${token(it)}" } ?: base
    }
}
