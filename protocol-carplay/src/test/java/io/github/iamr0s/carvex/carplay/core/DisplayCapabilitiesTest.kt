package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.*
import org.junit.Test
class DisplayCapabilitiesTest {
    private fun screen(vararg fields:Pair<String,Any?>)=mapOf<String,Any?>("widthPixels" to 1280L,"heightPixels" to 720L,"uuid" to "default")+fields
    private fun rejects(value:Any?){try{DisplayCapabilities.select(value);fail("Accepted invalid capabilities")}catch(expected:IllegalArgumentException){}catch(expected:IllegalStateException){}}
    @Test fun primaryHintWinsOverArea(){
        val p=DisplayCapabilities.select(listOf(screen("type" to 110,"widthPixels" to 1920,"uuid" to "large"),screen("type" to 0,"uuid" to "primary")))
        assertEquals("primary",p.uuid);assertEquals(15,p.fps)
    }
    @Test fun anyIntegralDisplayTypeAndMissingTypeAreCandidates(){
        for(type in listOf(110,1,777))assertEquals(type,DisplayCapabilities.select(listOf(screen("type" to type))).type)
        assertNull(DisplayCapabilities.select(listOf(screen())).type)
        rejects(listOf(screen("type" to "0")));rejects(listOf(screen("type" to null)));rejects(listOf(screen("type" to 110.5)))
    }
    @Test fun largestUsableAreaThenFpsThenAnnouncementOrder(){
        val small=screen("uuid" to "small","maxFPS" to 60)
        val large=screen("uuid" to "large","widthPixels" to 1920,"maxFPS" to 30)
        assertEquals("large",DisplayCapabilities.select(listOf(small,large)).uuid)
        assertEquals("small",DisplayCapabilities.select(listOf(small,screen("uuid" to "slow","maxFPS" to 30))).uuid)
        assertEquals(0,DisplayCapabilities.select(listOf(small,screen("uuid" to "tie","maxFPS" to 60))).index)
    }
    @Test fun encoderFilteringAndReducedRate(){
        val items=listOf(screen("type" to 0,"widthPixels" to 1920,"uuid" to "unsupported"),screen("type" to 110,"uuid" to "usable","maxFPS" to 60))
        val p=DisplayCapabilities.select(items,{w,_,limit->if(w==1920)null else minOf(limit,24)})
        assertEquals("usable",p.uuid);assertEquals(24,p.fps)
        rejects(listOf(screen("widthPixels" to Double.NaN)))
    }
    @Test fun malformedCandidatesDoNotHideUsableOnes(){
        assertEquals(1,DisplayCapabilities.select(listOf("bad",screen())).index)
        for(v in listOf(Double.POSITIVE_INFINITY,1280.5,Long.MAX_VALUE,"1280",null))rejects(listOf(screen("widthPixels" to v)))
        for(v in listOf(0,-1,29.5,null))rejects(listOf(screen("maxFPS" to v)))
        rejects(listOf(screen("heightPixels" to 0)));rejects(listOf(screen("uuid" to 1)))
    }
    @Test fun multiDisplayAddressingAndDuplicateIds(){
        rejects(listOf(screen(),screen()))
        val withoutId=mapOf("widthPixels" to 1280,"heightPixels" to 720)
        assertNull(DisplayCapabilities.select(listOf(withoutId)).uuid)
        rejects(listOf(withoutId,withoutId))
        assertEquals("valid",DisplayCapabilities.select(listOf(withoutId,screen("uuid" to "valid"))).uuid)
    }
    @Test fun observedPlistAndSafeDiagnostics(){
        val input=listOf(screen("type" to 110,"widthPixels" to 1920,"maxFPS" to 30,"uuid" to "private-identity","unknown" to "private-value"))
        val decoded=Plist.dictionary(Plist.encode(mapOf("displays" to input)))
        val logs=mutableListOf<String>()
        val p=DisplayCapabilities.select(decoded["displays"],log={logs.add(it)})
        assertEquals(1920,p.width);assertEquals(30,p.fps)
        assertFalse((logs+DisplayCapabilities.summary(decoded["displays"])).joinToString().contains("private"))
    }
    @Test fun portraitAnnouncementReachesEncoderWithoutTransposing(){
        var checked=false
        val item=screen("widthPixels" to 1600,"heightPixels" to 2560,"maxFPS" to 30)
        val selected=DisplayCapabilities.select(listOf(item),{w,h,limit->
            assertEquals(1600,w);assertEquals(2560,h);assertEquals(30,limit)
            checked=true;30
        })
        assertTrue(checked);assertEquals(2560,selected.height)
        val logs=mutableListOf<String>()
        assertThrows(IllegalStateException::class.java){DisplayCapabilities.select(listOf(item),{_,_,_->null},{logs.add(it)})}
        assertTrue(logs.any{it.contains("AVC surface encoder")})
    }
    @Test fun receiverRateAbove60IsPassedToActualCapabilityProbe(){
        val selected=DisplayCapabilities.select(listOf(screen("maxFPS" to 120)),{_,_,limit->assertEquals(120,limit);90})
        assertEquals(90,selected.fps)
    }
}
