package io.github.iamr0s.carvex.sessioncore

/**
 * State only for the stateless HID Consumer Play/Pause usage.
 *
 * CarWith exposes separate Play and Pause keys, not a toggle key. Keep a
 * conservative local state seeded by the main audio lane and update it when
 * explicit playback keys are delivered.
 */
class MediaPlaybackToggle {
    private var playing = false

    @Synchronized fun audioLaneActive(active: Boolean) {
        playing = active
    }

    @Synchronized fun observeExplicitKey(usage: Int) {
        when (usage) {
            0xb0 -> playing = true
            0xb1, 0xb7 -> playing = false
        }
    }

    @Synchronized fun choose(play: Int, pause: Int): Int {
        val code = if (playing) pause else play
        playing = !playing
        return code
    }

    @Synchronized fun isPlaying(): Boolean = playing
}
