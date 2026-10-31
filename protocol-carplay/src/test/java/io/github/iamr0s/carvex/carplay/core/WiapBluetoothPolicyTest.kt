package io.github.iamr0s.carvex.carplay.core

import io.github.iamr0s.carvex.carplay.core.WiapBluetoothPolicy.Basis
import io.github.iamr0s.carvex.carplay.core.WiapBluetoothPolicy.CacheObservation
import io.github.iamr0s.carvex.carplay.core.WiapBluetoothPolicy.Service
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WiapBluetoothPolicyTest {
    private val hfp = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")
    private val a2dp = UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb")

    private fun plan(vararg uuids: UUID) = WiapBluetoothPolicy.plan(uuids.toList(), false)
    private fun WiapBluetoothPolicy.Plan.services() = outbound.map { it.service }
    private fun WiapBluetoothPolicy.Plan.bases() = outbound.map { it.basis }

    @Test fun serviceRolesHaveExactFirmwareUuids() {
        assertEquals(UUID.fromString("00000000-deca-fade-deca-deafdecacafe"), Service.LEGACY.localUuid)
        assertEquals(UUID.fromString("00000000-deca-fade-deca-deafdecacaff"), Service.LEGACY.remoteUuid)
        assertEquals(UUID.fromString("02030302-1d19-415f-86f2-22a2106a0a77"), Service.V2.localUuid)
        assertEquals(UUID.fromString("02030302-1d19-415f-86f2-22a2106a0a78"), Service.V2.remoteUuid)
    }

    @Test fun unavailableCacheRemainsUnknownAndProbesBothServices() {
        val result = WiapBluetoothPolicy.plan(null, false)
        assertEquals(CacheObservation.UNAVAILABLE, result.observation)
        assertNull(result.cachedUuidCount)
        assertEquals(listOf(Service.V2, Service.LEGACY), result.services())
        assertEquals(listOf(Basis.PROBE_ON_UNKNOWN, Basis.PROBE_ON_UNKNOWN), result.bases())
    }

    @Test fun emptyCacheIsNotProofThatNeitherServiceExists() {
        val result = plan()
        assertEquals(CacheObservation.EMPTY, result.observation)
        assertEquals(0, result.cachedUuidCount)
        assertEquals(listOf(Service.V2, Service.LEGACY), result.services())
        assertEquals(listOf(Basis.PROBE_ON_UNKNOWN, Basis.PROBE_ON_UNKNOWN), result.bases())
    }

    @Test fun audioOnlyCacheNoLongerExcludesV2OrPretendsLegacyIsPresent() {
        val result = plan(hfp, a2dp)
        assertEquals(CacheObservation.NO_WIAP_HINT, result.observation)
        assertEquals(2, result.cachedUuidCount)
        assertEquals(listOf(Service.V2, Service.LEGACY), result.services())
        assertEquals(listOf(Basis.PROBE_ON_UNKNOWN, Basis.PROBE_ON_UNKNOWN), result.bases())
    }

    @Test fun phoneRoleUuidsAreNotRemoteAccessoryHints() {
        val result = plan(Service.LEGACY.localUuid, Service.V2.localUuid)
        assertEquals(CacheObservation.NO_WIAP_HINT, result.observation)
        assertEquals(listOf(Basis.PROBE_ON_UNKNOWN, Basis.PROBE_ON_UNKNOWN), result.bases())
    }

    @Test fun legacyHintKeepsItsFastPathButDoesNotExcludeUnknownV2() {
        val result = plan(Service.LEGACY.remoteUuid)
        assertEquals(CacheObservation.LEGACY_HINT, result.observation)
        assertEquals(listOf(Service.LEGACY, Service.V2), result.services())
        assertEquals(listOf(Basis.UUID_CACHE_HINT, Basis.PROBE_ON_UNKNOWN), result.bases())
    }

    @Test fun v2HintKeepsItsFastPathButDoesNotExcludeUnknownLegacy() {
        val result = plan(Service.V2.remoteUuid)
        assertEquals(CacheObservation.V2_HINT, result.observation)
        assertEquals(listOf(Service.V2, Service.LEGACY), result.services())
        assertEquals(listOf(Basis.UUID_CACHE_HINT, Basis.PROBE_ON_UNKNOWN), result.bases())
    }

    @Test fun bothHintsPreferV2WithoutClaimingFullSdpKnowledge() {
        val result = plan(Service.LEGACY.remoteUuid, Service.V2.remoteUuid)
        assertEquals(CacheObservation.BOTH_HINTS, result.observation)
        assertEquals(listOf(Service.V2, Service.LEGACY), result.services())
        assertEquals(listOf(Basis.UUID_CACHE_HINT, Basis.UUID_CACHE_HINT), result.bases())
    }

    @Test fun unrelatedHintsDoNotChangeLegacyFastPath() {
        assertEquals(plan(Service.LEGACY.remoteUuid).outbound,
            plan(hfp, a2dp, Service.LEGACY.remoteUuid, Service.V2.localUuid).outbound)
    }

    @Test fun duplicateUuidsNeverDuplicateConnectAttempts() {
        val result = plan(Service.V2.remoteUuid, Service.V2.remoteUuid, Service.LEGACY.remoteUuid)
        assertEquals(2, result.cachedUuidCount)
        assertEquals(plan(Service.LEGACY.remoteUuid, Service.V2.remoteUuid).outbound, result.outbound)
    }

    @Test fun uuidCacheOrderingDoesNotSelectTheProtocolOrder() {
        assertEquals(plan(Service.V2.remoteUuid, hfp, Service.LEGACY.remoteUuid),
            plan(Service.LEGACY.remoteUuid, hfp, Service.V2.remoteUuid))
    }

    @Test fun planDoesNotRetainMutablePlatformCache() {
        val cache = mutableListOf(Service.LEGACY.remoteUuid)
        val result = WiapBluetoothPolicy.plan(cache, false)
        cache.clear()
        cache += Service.V2.remoteUuid
        assertEquals(CacheObservation.LEGACY_HINT, result.observation)
        assertEquals(1, result.cachedUuidCount)
        assertEquals(listOf(Service.LEGACY, Service.V2), result.services())
    }

    @Test fun listenOnlyNeverDialsRegardlessOfCacheHints() {
        val caches = listOf(null, emptyList(), listOf(hfp), listOf(Service.LEGACY.remoteUuid),
            listOf(Service.V2.remoteUuid), listOf(Service.LEGACY.remoteUuid, Service.V2.remoteUuid))
        for (cache in caches) {
            val result = WiapBluetoothPolicy.plan(cache, true)
            assertTrue(result.outbound.isEmpty())
            assertEquals(WiapBluetoothPolicy.plan(cache, false).observation, result.observation)
        }
    }

    @Test fun everyHintCombinationHasExactlyTwoDistinctRemoteCandidates() {
        val possible = listOf(hfp, a2dp, Service.LEGACY.localUuid, Service.V2.localUuid,
            Service.LEGACY.remoteUuid, Service.V2.remoteUuid)
        for (mask in 0 until (1 shl possible.size)) {
            val cache = possible.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            val result = WiapBluetoothPolicy.plan(cache, false)
            assertEquals("mask=$mask", 2, result.outbound.size)
            assertEquals(setOf(Service.LEGACY, Service.V2), result.services().toSet())
            for (candidate in result.outbound) {
                assertEquals(candidate.service.remoteUuid in cache, candidate.basis == Basis.UUID_CACHE_HINT)
                assertFalse(candidate.service.remoteUuid in Service.entries.map { it.localUuid })
            }
        }
    }

    @Test fun productionRegistrationHelperPublishesOnlyTwoPhoneRoleServicesInOrder() {
        val requested = mutableListOf<UUID>()
        val handles = mapOf(Service.LEGACY to Any(), Service.V2 to Any())
        val listeners = WiapBluetoothPolicy.openPhoneListeners { service ->
            requested += service.localUuid
            handles.getValue(service)
        }
        assertEquals(listOf(Service.LEGACY.localUuid, Service.V2.localUuid), requested)
        assertEquals(listOf(Service.LEGACY, Service.V2), listeners.map { it.service })
        assertSame(handles[Service.LEGACY], listeners[0].socket)
        assertSame(handles[Service.V2], listeners[1].socket)
        assertTrue(requested.none { uuid -> Service.entries.any { it.remoteUuid == uuid } })
    }

    @Test fun failedLegacyRegistrationDoesNotPublishV2OrSinkInstead() {
        val requested = mutableListOf<Service>()
        val listeners = WiapBluetoothPolicy.openPhoneListeners<Any> { service ->
            requested += service
            null
        }
        assertEquals(listOf(Service.LEGACY), requested)
        assertTrue(listeners.isEmpty())
    }

    @Test fun failedV2RegistrationRetainsSuccessfulLegacyListener() {
        val legacyHandle = Any()
        val requested = mutableListOf<Service>()
        val listeners = WiapBluetoothPolicy.openPhoneListeners { service ->
            requested += service
            if (service == Service.LEGACY) legacyHandle else null
        }
        assertEquals(listOf(Service.LEGACY, Service.V2), requested)
        assertEquals(1, listeners.size)
        assertSame(legacyHandle, listeners.single().socket)
        assertEquals(Service.LEGACY, listeners.single().service)
    }

    @Test fun fatalRegistrationErrorIsNotSwallowedOrTurnedIntoAnotherRole() {
        val failure = SecurityException("permission denied")
        val requested = mutableListOf<Service>()
        val thrown = assertThrows(SecurityException::class.java) {
            WiapBluetoothPolicy.openPhoneListeners<Any> { service ->
                requested += service
                throw failure
            }
        }
        assertSame(failure, thrown)
        assertEquals(listOf(Service.LEGACY), requested)
    }
}
