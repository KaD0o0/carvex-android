package io.github.iamr0s.carvex.ui

import io.github.iamr0s.carvex.sessioncore.SessionState
import io.github.iamr0s.carvex.sessioncore.Stage
import io.github.iamr0s.carvex.sessioncore.progressText
import io.github.iamr0s.carvex.ui.model.Link as DeviceLink
import io.github.iamr0s.carvex.ui.model.UiState
import io.github.iamr0s.carvex.ui.model.withSession
import org.junit.Assert.*
import org.junit.Test

class ConnectionProgressTest {
    @Test fun displayNegotiationConnectsBeforeAnyVideoFrame() {
        var ui = UiState().withSession(SessionState(id = "a", stage = Stage.PAIR), 7, now = 10)
        assertTrue(ui.link is DeviceLink.Connecting)
        ui = ui.withSession(SessionState(id = "a", stage = Stage.SETUP, frames = 0), 7, now = 20)
        assertEquals(20L, (ui.link as DeviceLink.Connected).since)
        ui = ui.withSession(SessionState(id = "a", stage = Stage.WAIT_FRAME), 7, now = 30)
        assertEquals(20L, (ui.link as DeviceLink.Connected).since)
    }

    @Test fun discoveryRefreshPreservesConnectedStateAndNewSessionResetsUptime() {
        val session = SessionState(id = "a", stage = Stage.STREAMING)
        val ui = UiState().withSession(session, 7, now = 10).withSession(session, 7, now = 20)
        assertEquals(10L, (ui.link as DeviceLink.Connected).since)
        assertEquals(30L, (ui.withSession(session.copy(id = "b"), 7, now = 30).link as DeviceLink.Connected).since)
    }

    @Test fun progressSurvivesMissingDeviceAndKeepsNotificationDetail() {
        val session = SessionState(stage = Stage.RETRY, detail = "资源已释放，等待恢复连接")
        val ui = UiState().withSession(session, null)
        assertEquals(Stage.RETRY, ui.progress.stage)
        assertEquals(session.progressText, ui.progress.detail)
    }

    @Test fun failureAndStoppingCannotKeepConnectedAppearance() {
        val ui = UiState().withSession(SessionState(id = "a", stage = Stage.SETUP), 7)
        val failure = SessionState(id = "a", stage = Stage.FAILED, detail = "画面协商失败", error = "超时")
        val failed = ui.withSession(failure, 7)
        assertTrue(failed.link is DeviceLink.Idle)
        assertEquals("画面协商失败：超时", failed.progress.detail)
        val stopping = ui.withSession(failure.copy(stage = Stage.STOPPING, detail = Stage.STOPPING.title), 7)
        assertFalse(stopping.link is DeviceLink.Connected)
        assertEquals(Stage.STOPPING.title, stopping.progress.detail)
    }

    @Test fun emptyDetailFallsBackToStageTitle() {
        val session = SessionState(stage = Stage.SETUP, detail = "")
        assertEquals(Stage.SETUP.title, UiState().withSession(session, 7).progress.detail)
    }
}
