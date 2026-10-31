package io.github.iamr0s.carvex.host

import io.github.iamr0s.carvex.sessioncore.HostKeyMapper

/** Compatibility names; the shared CarLife mixing table is maintained in [MixingKeys]. */
object CarWithKeys {
    const val PREVIOUS_TRACK=MixingKeys.PREVIOUS_TRACK
    const val NEXT_TRACK=MixingKeys.NEXT_TRACK
    const val HOME=MixingKeys.HOME
    const val PHONE=MixingKeys.PHONE
    const val NAVIGATION=MixingKeys.NAVIGATION
    const val MEDIA=MixingKeys.MEDIA
    const val START_VOICE=MixingKeys.START_VOICE
    const val STOP_VOICE=MixingKeys.STOP_VOICE
    const val PLAY=MixingKeys.PLAY
    const val PAUSE=MixingKeys.PAUSE
    /** Historical source alias only; the actual Android action is pause, not stop (86). */
    const val MEDIA_STOP=PAUSE
    const val CALL=MixingKeys.CALL
    const val END_CALL=MixingKeys.END_CALL
    const val EXIT_NAVIGATION=MixingKeys.EXIT_NAVIGATION
    const val NEXT_FOCUS=MixingKeys.NEXT_FOCUS
    const val PREVIOUS_FOCUS=MixingKeys.PREVIOUS_FOCUS
    const val MUTE=MixingKeys.MUTE

    /** 键码表的中文名，仅用于界面展示与按键调节；不改变任何转发行为。 */
    val catalog:List<Pair<Int,String>> =MixingKeys.catalog
}

/** Compatibility facade for callers using the original name. */
object CarWithKeyMapper : HostKeyMapper by MixingKeyMapper()
