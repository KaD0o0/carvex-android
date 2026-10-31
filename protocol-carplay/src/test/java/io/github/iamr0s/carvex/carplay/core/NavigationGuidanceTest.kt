package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationGuidanceTest {
    private fun fields(vararg pairs: Pair<Int, ByteArray>) = IapFields(pairs.toList())
    private fun u16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())

    @Test fun componentsAreDecodedAndRepeatedEntriesAggregate(){
        val started = NavigationGuidance.start(fields(0 to u16(7), 0 to u16(9), 1 to ByteArray(0), 4 to ByteArray(0)))
        assertEquals(listOf(7, 9), started!!.components)
        // p1 → 位 0，p4 → 位 4
        assertEquals(0b10001, started.mask)
        assertTrue(NavigationGuidance.active)
    }

    @Test fun feature17GateRequiresTheFourRequiredMessagesButNotOptional5204(){
        assertTrue(NavigationGuidance.featureReady(setOf(0x5200,0x5203),setOf(0x5201,0x5202)))
        assertTrue(NavigationGuidance.featureReady(setOf(0x5200,0x5203),setOf(0x5201,0x5202,0x5204)))
        assertFalse(NavigationGuidance.featureReady(setOf(0x5200),setOf(0x5201,0x5202)))
        assertFalse(NavigationGuidance.featureReady(setOf(0x5200,0x5203),setOf(0x5201)))
    }

    @Test fun nonEmptyPayloadOnOneToFiveRejectsTheWholeMessage(){
        assertNull(NavigationGuidance.start(fields(2 to byteArrayOf(1))))
        assertNull(NavigationGuidance.start(fields(0 to byteArrayOf(1))))
    }

    @Test fun repeatedOneToFiveIsRejected(){
        assertNull(NavigationGuidance.start(fields(3 to ByteArray(0), 3 to ByteArray(0))))
    }

    @Test fun unknownIdsAreIgnored(){
        val started = NavigationGuidance.start(fields(0 to u16(1), 9 to ByteArray(0)))
        assertEquals(listOf(1), started!!.components)
        assertEquals(0, started.mask)
    }

    @Test fun stopAcceptsOnlyComponentIds(){
        NavigationGuidance.start(fields(0 to u16(1)))
        NavigationGuidance.stop(fields(0 to u16(3), 2 to byteArrayOf(0)))
        assertFalse(NavigationGuidance.active)
        assertEquals(listOf(3), NavigationGuidance.components)
    }

    @Test fun emptyStopClearsTheSessionButMalformedComponentDoesNot(){
        NavigationGuidance.start(fields(0 to u16(1)))
        assertTrue(NavigationGuidance.stop(fields()))
        assertFalse(NavigationGuidance.active)
        assertFalse(NavigationGuidance.stop(fields(0 to byteArrayOf(1))))
        assertFalse(NavigationGuidance.active)
    }

    @Test fun timezoneUsesP4MaskBitAndNotTheDerivedP5204Bit(){
        NavigationGuidance.start(fields(4 to ByteArray(0)))
        val withP4=NavigationGuidance.route(NavigationGuidance.RouteUpdate(
            components=listOf(1), timezoneOffset=60))
        assertTrue(withP4.any { it.first==21 })

        NavigationGuidance.start(fields(3 to ByteArray(0)))
        val withOnlyDerivedBit=NavigationGuidance.route(NavigationGuidance.RouteUpdate(
            components=listOf(1), timezoneOffset=60))
        assertFalse(withOnlyDerivedBit.any { it.first==21 })
    }

    @Test fun producerEncodersValidateWidthsPerChapter16(){
        val rg = NavigationGuidance.routeGuidanceUpdate(listOf(
            0 to u16(1) + u16(2), 3 to IapWire.cstring("Main St"), 7 to IapWire.be32(120)))
        assertEquals(listOf(0, 3, 7), rg.map { it.first })
        val maneuver = NavigationGuidance.maneuverUpdate(listOf(1 to u16(3), 5 to IapWire.be32(9)))
        assertEquals(listOf(1, 5), maneuver.map { it.first })
        val lane = NavigationGuidance.laneGuidance(listOf(0 to u16(1), 3 to IapWire.cstring("keep left")))
        assertEquals(listOf(0, 3), lane.map { it.first })
        // 宽度错误必须被拒绝，而不是把坏值发出去。
        assertThrows(IllegalArgumentException::class.java) {
            NavigationGuidance.routeGuidanceUpdate(listOf(7 to ByteArray(2)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NavigationGuidance.maneuverUpdate(listOf(1 to ByteArray(1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NavigationGuidance.laneGuidance(listOf(0x1f to ByteArray(2)))
        }
    }

    @Test fun structuredProducersUseRepeatedComponentsAndNestedLaneGroups(){
        val route=NavigationGuidance.route(NavigationGuidance.RouteUpdate(listOf(7,9),currentRoad="Main",distanceRemaining=42))
        assertEquals(listOf(0,0,3,7),route.map{it.first})
        assertEquals(listOf(0,2,2),NavigationGuidance.lane(NavigationGuidance.LaneUpdate(
            listOf(7),lanes=listOf(NavigationGuidance.Lane(status=1,angles=listOf(90)),NavigationGuidance.Lane(status=2)))).map{it.first})
        val laneGroup=route // keep the route construction above visible in a debugger
        assertTrue(laneGroup.isNotEmpty())
    }
}
