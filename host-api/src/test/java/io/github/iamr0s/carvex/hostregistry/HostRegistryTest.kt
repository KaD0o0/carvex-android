package io.github.iamr0s.carvex.hostregistry

import io.github.iamr0s.carvex.sessioncore.CastHost
import io.github.iamr0s.carvex.sessioncore.HostCallbacks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HostRegistryTest {
    private class Plugin(override val id:String,override val displayName:String="x",
                         private val available:Boolean=false):HostPlugin {
        var created=false
        override fun isAvailable(context:android.content.Context):Boolean=available
        override fun create(context:android.content.Context,callbacks:HostCallbacks):CastHost {
            created=true
            throw UnsupportedOperationException("fake")
        }
    }

    @Before fun setUp()=HostRegistry.clear()
    @After fun tearDown()=HostRegistry.clear()

    @Test fun installKeepsRegistrationOrderAndReplacesDuplicateId(){
        HostRegistry.install(Plugin("a"),Plugin("b"))
        HostRegistry.install(Plugin("b",displayName="b2"))
        assertEquals(listOf("a","b"),HostRegistry.installed().map{it.id})
        assertEquals("b2",HostRegistry.byId("b")?.displayName)
    }

    @Test fun availabilityProbeFiltersAndExceptionsMeanUnavailable(){
        HostRegistry.install(Plugin("ok"),Plugin("no"))
        val available=HostRegistry.available{it.id=="ok"}
        assertEquals(listOf("ok"),available.map{it.id})
        assertNull(HostRegistry.select(null){error("probe failed")})
    }

    @Test fun selectPrefersRequestedIdThenFirstAvailable(){
        HostRegistry.install(Plugin("a"),Plugin("b"))
        assertEquals("b",HostRegistry.select("b"){true}?.id)
        // 请求的 id 不可用 → 回退到第一个可用
        assertEquals("a",HostRegistry.select("b"){it.id=="a"}?.id)
        // 请求的 id 不存在 → 第一个可用
        assertEquals("a",HostRegistry.select("missing"){true}?.id)
        assertNull(HostRegistry.select(null){false})
    }
}
