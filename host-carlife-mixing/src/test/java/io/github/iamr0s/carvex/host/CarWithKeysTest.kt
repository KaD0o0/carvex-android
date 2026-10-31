package io.github.iamr0s.carvex.host

import org.junit.Assert.*
import org.junit.Test

/** The host voice action uses 8107=start and 8108=stop. Both are handled on
 *  ACTION_DOWN; the bridge sends a matching UP only to clear its local state. */
class CarWithKeysTest {
    @Test fun theVoicePathUsesTheVersionedStartAndStopCodes(){
        assertEquals(8107,CarWithKeys.START_VOICE)
        assertEquals(8108,CarWithKeys.STOP_VOICE)
        assertNotEquals(CarWithKeys.START_VOICE,CarWithKeys.STOP_VOICE)
    }
    @Test fun theTableIsTheFullContiguousCarWithRange(){
        val keys=listOf(CarWithKeys.PREVIOUS_TRACK,CarWithKeys.NEXT_TRACK,CarWithKeys.HOME,CarWithKeys.PHONE,
            CarWithKeys.NAVIGATION,CarWithKeys.MEDIA,CarWithKeys.START_VOICE,CarWithKeys.STOP_VOICE,
            CarWithKeys.PLAY,CarWithKeys.PAUSE,CarWithKeys.CALL,CarWithKeys.END_CALL,
            CarWithKeys.EXIT_NAVIGATION,CarWithKeys.NEXT_FOCUS,CarWithKeys.PREVIOUS_FOCUS)
        assertEquals(8101,keys.min());assertEquals(8115,keys.max())
        assertEquals("every code in 8101..8115 appears exactly once",keys.size,keys.toSet().size)
    }
    @Test fun mapperAnswersSemanticQuestionsInsteadOfProtocolConstants(){
        val mapper=CarWithKeyMapper
        assertEquals(CarWithKeys.START_VOICE,mapper.voiceStart())
        assertEquals(CarWithKeys.STOP_VOICE,mapper.voiceStop())
        assertEquals(CarWithKeys.PLAY,mapper.mediaKey(0xb0))
        assertEquals(CarWithKeys.PAUSE,mapper.mediaKey(0xb1))
        assertEquals(CarWithKeys.NEXT_TRACK,mapper.mediaKey(0xb5))
        assertEquals(CarWithKeys.PREVIOUS_TRACK,mapper.mediaKey(0xb6))
        assertNull(mapper.mediaKey(0xcd))
        assertEquals(CarWithKeys.NAVIGATION,mapper.uiKey("ui:maps:"))
        assertEquals(CarWithKeys.MEDIA,mapper.uiKey("ui:nowplaying:"))
        assertEquals(CarWithKeys.PHONE,mapper.uiKey("ui:mobilephone:"))
        assertNull(mapper.uiKey("ui:unknown:"))
    }
}
