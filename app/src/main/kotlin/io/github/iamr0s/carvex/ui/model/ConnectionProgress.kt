package io.github.iamr0s.carvex.ui.model

import io.github.iamr0s.carvex.sessioncore.SessionState
import io.github.iamr0s.carvex.sessioncore.Stage
import io.github.iamr0s.carvex.sessioncore.progressText

data class ConnectionProgress(
    val sessionId: String = "",
    val stage: Stage = Stage.IDLE,
    val detail: String = SessionState().progressText,
)

/** Also used after device discovery, so a refresh cannot downgrade an established link. */
internal fun UiState.withSession(
    session: SessionState,
    deviceId: Int?,
    method: Method = Method.Bluetooth,
    now: Long = System.currentTimeMillis(),
): UiState {
    val previous = link as? Link.Connected
    val next = when {
        deviceId == null || session.stage == Stage.IDLE || session.stage == Stage.FAILED -> Link.Idle
        session.stage.connected -> Link.Connected(
            deviceId, method, -1,
            previous?.takeIf { it.deviceId == deviceId && progress.sessionId == session.id }?.since ?: now,
        )
        else -> Link.Connecting(deviceId, method)
    }
    return copy(link = next, progress = ConnectionProgress(session.id, session.stage, session.progressText))
}
