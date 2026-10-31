package io.github.iamr0s.carvex.sessioncore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackToggleTest {
    @Test fun statelessHidToggleAlternatesHostPlayAndPause() {
        val state=MediaPlaybackToggle()
        assertEquals(100,state.choose(100,101))
        assertTrue(state.isPlaying())
        assertEquals(101,state.choose(100,101))
        assertFalse(state.isPlaying())
    }

    @Test fun explicitKeysAndAudioLaneSeedTheNextToggle() {
        val state=MediaPlaybackToggle()
        state.observeExplicitKey(0xb0)
        assertEquals(101,state.choose(100,101))

        state.audioLaneActive(true)
        assertEquals(101,state.choose(100,101))

        state.observeExplicitKey(0xb7)
        assertEquals(100,state.choose(100,101))
    }
}
