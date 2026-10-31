package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AudioTeardownDescriptorTest {
    @Test fun firmwareSingleStreamTeardownContainsOnlyRawType(){
        val descriptor=AudioTeardownDescriptor.forType(100)
        assertEquals(mapOf<String,Any?>("type" to 100),descriptor)
        assertFalse(descriptor.containsKey("streamID"))
        assertFalse(descriptor.containsKey("streamConnectionID"))
    }
}
