package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class AudioLatencyCapabilitiesTest {
    @Test fun retainsExactRouteMicrosecondValuesAndFormatQualifierKeys(){
        val logs=mutableListOf<String>()
        val entries=AudioLatencyCapabilities.parse(listOf(mapOf(
            "type" to 102,"audioType" to "media","inputLatencyMicros" to 0L,
            "outputLatencyMicros" to 35000L,"sr" to 48000,"ch" to 2,"ss" to 16,
            "sampleRate" to 44100,"unrelatedSecret" to "must-not-log")),logs::add)
        val entry=entries.single()
        assertEquals(102,entry.streamType);assertEquals("media",entry.audioType)
        assertEquals(0L,entry.inputLatencyMicros);assertEquals(35000L,entry.outputLatencyMicros)
        assertEquals(mapOf("sampleRate" to 44100L,"sr" to 48000L,"ch" to 2L,"ss" to 16L),entry.formatFields)
        assertTrue(logs.single().contains("outputLatencyMicros=35000"))
        assertFalse(logs.single().contains("must-not-log"))
        assertTrue(AudioLatencyCapabilities.summary(entries,102,"media").contains("sr=48000"))
    }
    @Test fun routeQueryDoesNotMergePurposesDirectionsOrDifferentFormatRows(){
        val entries=AudioLatencyCapabilities.parse(listOf(
            mapOf("type" to 102,"audioType" to "media","sr" to 48000,"outputLatencyMicros" to 35000),
            mapOf("type" to 102,"audioType" to "media","sr" to 44100,"outputLatencyMicros" to 45000),
            mapOf("type" to 100,"audioType" to "media","outputLatencyMicros" to 10000),
            mapOf("type" to 102,"audioType" to "alert","outputLatencyMicros" to 5000),
            mapOf("type" to 102,"outputLatencyMicros" to 9000)))
        val matches=AudioLatencyCapabilities.matching(entries,102,"media")
        assertEquals(listOf(35000L,45000L),matches.map{it.outputLatencyMicros})
        assertTrue(matches.all{it.inputLatencyMicros==null})
        assertEquals("none",AudioLatencyCapabilities.summary(entries,103,"media"))
    }
    @Test fun malformedOptionalNumbersRemainUnknownInsteadOfBecomingZero(){
        val logs=mutableListOf<String>()
        for(value in listOf(-1L,1.5,Double.NaN,Double.POSITIVE_INFINITY,Long.MAX_VALUE.toDouble(),"35000",true)){
            val entry=AudioLatencyCapabilities.parse(listOf(mapOf("type" to 102,"audioType" to "media",
                "inputLatencyMicros" to value,"outputLatencyMicros" to value,"sr" to value)),logs::add).single()
            assertNull(entry.inputLatencyMicros);assertNull(entry.outputLatencyMicros)
            assertTrue(entry.formatFields.isEmpty())
        }
        assertTrue(logs.all{it.contains("invalidFields=inputLatencyMicros,outputLatencyMicros,sr")})
    }
    @Test fun exactNonnegativeNumbersAndMissingFieldsArePreserved(){
        val entries=AudioLatencyCapabilities.parse(listOf(
            mapOf("type" to 102.0,"audioType" to "media","inputLatencyMicros" to 0.0,
                "outputLatencyMicros" to 35000.0),
            mapOf("type" to 111,"outputLatencyMicros" to Long.MAX_VALUE),
            mapOf("type" to 102,"audioType" to "media")))
        assertEquals(0L,entries[0].inputLatencyMicros);assertEquals(35000L,entries[0].outputLatencyMicros)
        assertEquals(Long.MAX_VALUE,entries[1].outputLatencyMicros)
        assertNull(entries[2].inputLatencyMicros);assertNull(entries[2].outputLatencyMicros)
    }
    @Test fun invalidIdentitiesAndOversizedContainersAreRejectedWithoutExceptions(){
        val rows=listOf(null,"not a row",mapOf("type" to 102.5),mapOf("type" to -1),
            mapOf("type" to Long.MAX_VALUE),mapOf("audioType" to "media"),
            mapOf("type" to 102,"audioType" to 1),mapOf("type" to 102,"audioType" to "a".repeat(129)),
            mapOf("type" to 102,"audioType" to "media\nforged"))
        assertTrue(AudioLatencyCapabilities.parse(rows).isEmpty())
        assertTrue(AudioLatencyCapabilities.parse(null).isEmpty())
        assertTrue(AudioLatencyCapabilities.parse(mapOf("type" to 102)).isEmpty())
        assertTrue(AudioLatencyCapabilities.parse(List(257){mapOf("type" to 102)}).isEmpty())
    }
}
