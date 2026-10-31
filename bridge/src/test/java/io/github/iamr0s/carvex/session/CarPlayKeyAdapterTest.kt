package io.github.iamr0s.carvex.session

import io.github.iamr0s.carvex.sessioncore.HostKeyMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 翻译层测试：协议侧只给语义，码表由宿主提供；这里用一个假宿主固定语义。 */
class CarPlayKeyAdapterTest {
    private object FakeMapper:HostKeyMapper {
        override fun voiceStart():Int=100
        override fun voiceStop():Int=101
        override fun mediaKey(usage:Int):Int?=when(usage){
            0xb0->200; 0xb1->201; 0xb5->202; 0xb6->203; else->null
        }
        override fun hidKey(prefix:String,usage:Int):Int?=when(prefix){
            "media"->mediaKey(usage)
            "pageb"->when(usage){0x20->400;0x21->401;0x26->402;else->null}
            else->null
        }
        override fun uiKey(eventId:String):Int?=when{
            eventId.startsWith("ui:maps:")->300
            eventId.startsWith("ui:nowplaying:")->301
            eventId.startsWith("ui:mobilephone:")->302
            else->null
        }
    }

    private val adapter=CarPlayKeyAdapter(FakeMapper)

    @Test fun documentedMediaUsagesUseHostCodes(){
        assertEquals(200,adapter.mediaUsage(0xb0))
        assertEquals(201,adapter.mediaUsage(0xb1))
        assertEquals(202,adapter.mediaUsage(0xb5))
        assertEquals(203,adapter.mediaUsage(0xb6))
        assertNull(adapter.mediaUsage(0xcd))
    }

    @Test fun telephonyPageUsesPageQualifiedHostCodes(){
        assertEquals(400,adapter.hidEvent("pageb",0x20))
        assertEquals(401,adapter.hidEvent("pageb",0x21))
        assertEquals(402,adapter.hidEvent("pageb",0x26))
        assertNull(adapter.hidEvent("pageb",0x7f))
        assertNull(adapter.hidEvent("keyboard",0x20))
    }

    @Test fun documentedUiSchemesUseHostCodes(){
        assertEquals(300,adapter.uiEvent("ui:maps:"))
        assertEquals(301,adapter.uiEvent("ui:nowplaying:"))
        assertEquals(302,adapter.uiEvent("ui:mobilephone:"))
        assertNull(adapter.uiEvent("ui:unknown:"))
    }

    @Test fun withoutHostMapperEverythingIsUnsupported(){
        val none=CarPlayKeyAdapter(null)
        assertNull(none.mediaUsage(0xb0))
        assertNull(none.uiEvent("ui:maps:"))
        // Android 系统媒体键不依赖宿主码表，仍然可用。
        assertEquals(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,none.systemMediaUsage(0xcd))
    }

    @Test fun pauseAndToggleHaveDistinctStandardMediaSessionSemantics(){
        assertNull(adapter.systemMediaUsage(0xb1))
        assertEquals(201,adapter.mediaUsage(0xb1))
        assertEquals(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,adapter.systemMediaUsage(0xcd))
        assertNull(adapter.systemMediaUsage(0xb5))
        assertNull(adapter.systemMediaUsage(0xffff))
    }
}
