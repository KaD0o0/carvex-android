package io.github.iamr0s.carvex.host

import io.github.iamr0s.carvex.sessioncore.HostKeyMapper

/** Shared CommonConstants.TEST_KEY_* table verified in all four CarLife component APKs. */
object MixingKeys {
    const val PREVIOUS_TRACK=8101
    const val NEXT_TRACK=8102
    const val HOME=8103
    const val PHONE=8104
    const val NAVIGATION=8105
    const val MEDIA=8106
    const val START_VOICE=8107
    const val STOP_VOICE=8108
    const val PLAY=8109
    /** The original constant is named MEDIA_STOP; the verified media action is pause. */
    const val PAUSE=8110
    const val CALL=8111
    const val END_CALL=8112
    const val EXIT_NAVIGATION=8113
    const val NEXT_FOCUS=8114
    const val PREVIOUS_FOCUS=8115
    const val MUTE=8116

    val catalog:List<Pair<Int,String>> =listOf(
        PREVIOUS_TRACK to "上一曲",NEXT_TRACK to "下一曲",HOME to "主页",PHONE to "电话",
        NAVIGATION to "导航",MEDIA to "媒体",START_VOICE to "语音开始",STOP_VOICE to "语音结束",
        PLAY to "播放",PAUSE to "暂停",CALL to "接听",END_CALL to "挂断",
        EXIT_NAVIGATION to "退出导航",NEXT_FOCUS to "下一焦点",PREVIOUS_FOCUS to "上一焦点",
        MUTE to "静音")
}

/** One mapper for all four verified mixing component variants. */
class MixingKeyMapper : HostKeyMapper {
    override fun voiceStart():Int = MixingKeys.START_VOICE
    override fun voiceStop():Int = MixingKeys.STOP_VOICE

    override fun mediaKey(usage:Int):Int? = when(usage) {
        0xb0 -> MixingKeys.PLAY
        0xb1 -> MixingKeys.PAUSE
        0xb5 -> MixingKeys.NEXT_TRACK
        0xb6 -> MixingKeys.PREVIOUS_TRACK
        0xb7 -> MixingKeys.PAUSE
        0xe2 -> MixingKeys.MUTE
        else -> null
    }

    override fun hidKey(prefix:String,usage:Int):Int? = when(prefix) {
        "media" -> mediaKey(usage)
        "pageb" -> when(usage) {
            0x20 -> MixingKeys.PHONE
            0x21 -> MixingKeys.CALL
            0x26 -> MixingKeys.END_CALL
            else -> null
        }
        else -> null
    }

    override fun uiKey(eventId:String):Int? = when {
        eventId.startsWith("ui:maps:") -> MixingKeys.NAVIGATION
        eventId.startsWith("ui:nowplaying:") -> MixingKeys.MEDIA
        eventId.startsWith("ui:mobilephone:") -> MixingKeys.PHONE
        else -> null
    }
}
