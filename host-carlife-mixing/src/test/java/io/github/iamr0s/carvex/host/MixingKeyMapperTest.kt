package io.github.iamr0s.carvex.host

import org.junit.Assert.*
import org.junit.Test

class MixingKeyMapperTest {
    @Test fun legacyNamesDelegateToTheSharedTable() {
        val common=MixingKeyMapper()
        assertSame(MixingKeys.catalog,CarWithKeys.catalog)
        assertEquals(common.voiceStart(),CarWithKeyMapper.voiceStart())
        assertEquals(common.voiceStop(),CarWithKeyMapper.voiceStop())
        for(usage in listOf(0xb0,0xb1,0xb5,0xb6,0xcd)) {
            assertEquals(common.mediaKey(usage),CarWithKeyMapper.mediaKey(usage))
        }
    }

    @Test fun documentedConsumerAndTelephonyUsagesHaveCarWithTargets() {
        val mapper=MixingKeyMapper()
        assertEquals(MixingKeys.PLAY,mapper.mediaKey(0xb0))
        assertEquals(MixingKeys.PAUSE,mapper.mediaKey(0xb1))
        assertEquals(MixingKeys.NEXT_TRACK,mapper.mediaKey(0xb5))
        assertEquals(MixingKeys.PREVIOUS_TRACK,mapper.mediaKey(0xb6))
        assertEquals(MixingKeys.PAUSE,mapper.mediaKey(0xb7))
        assertEquals(MixingKeys.MUTE,mapper.mediaKey(0xe2))

        assertEquals(MixingKeys.PHONE,mapper.hidKey("pageb",0x20))
        assertEquals(MixingKeys.CALL,mapper.hidKey("pageb",0x21))
        assertEquals(MixingKeys.END_CALL,mapper.hidKey("pageb",0x26))
        assertNull(mapper.hidKey("pageb",0x7f))
    }
}
