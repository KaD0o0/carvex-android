package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.*
import org.junit.Test
class ProjectionGeometryTest {
    @Test fun proportionalPortraitScalingMapsCornersAndCenter(){
        val g=ProjectionGeometry(1600,2560,800,1280)
        val corner=g.map(TouchSample(true,1599f,2559f))
        assertEquals(799f,corner.x,0.01f);assertEquals(1279f,corner.y,0.01f)
        val center=g.map(TouchSample(true,799.5f,1279.5f))
        assertEquals(399.5f,center.x,0.01f);assertEquals(639.5f,center.y,0.01f)
        assertFalse(g.map(TouchSample(false,0f,0f)).pressed)
    }
    @Test fun originalSizeAndInvalidLayouts(){
        val g=ProjectionGeometry(1920,720,1920,720)
        assertEquals(TouchSample(true,100f,200f),g.map(TouchSample(true,100f,200f)))
        assertThrows(IllegalArgumentException::class.java){ProjectionGeometry(1600,2560,1280,720)}
        assertThrows(IllegalArgumentException::class.java){ProjectionGeometry(1600,2560,2560,1600)}
        assertThrows(IllegalArgumentException::class.java){ProjectionGeometry(1600,2560,0,0)}
    }
}
