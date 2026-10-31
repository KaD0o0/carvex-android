package io.github.iamr0s.carvex.host.mixing.service

import org.junit.Assert.*
import org.junit.Test

class MixingBrandTest {
    @Test fun eachVerifiedBrandAcceptsItsOwnEntriesButRejectsOtherBrands() {
        val verified = MixingBrand.all.filter { it.hostServiceClasses.isNotEmpty() }
        for (target in verified) {
            for (source in verified) {
                for (serviceClass in source.hostServiceClasses) {
                    assertEquals("${target.id} entry from ${source.id}/$serviceClass", target == source,
                        target.acceptsHostIntent(source.hostPackage, serviceClass, source.manufacturer, 0, false))
                }
            }
        }
    }

    @Test fun aKnownPackageDoesNotAuthorizeUnknownClassesOrInvalidLaunchParameters() {
        val target = MixingBrand.forFlavor("xiaomi")
        val service = target.hostServiceClasses.single()
        assertTrue(target.acceptsHostIntent(target.hostPackage, service, target.manufacturer, 1, false))
        assertFalse(target.acceptsHostIntent(target.hostPackage, "unverified.Service", target.manufacturer, 0, false))
        assertFalse(target.acceptsHostIntent(target.hostPackage, service, "oppo", 0, false))
        assertFalse(target.acceptsHostIntent(target.hostPackage, service, target.manufacturer, -1, false))
        assertFalse(target.acceptsHostIntent(target.hostPackage, service, target.manufacturer, 2, false))
        assertFalse(target.acceptsHostIntent(target.hostPackage, service, target.manufacturer, 0, true))
        assertFalse(target.acceptsHostIntent(null, null, null, 0, false))
    }

    @Test fun samsungAcceptsBothDocumentedServiceClasses() {
        val target = MixingBrand.forFlavor("samsung")
        for (serviceClass in listOf("com.samsung.android.carlink.mixing.CarLifeMixingService",
            "com.samsung.android.carlink.carlife2.CarLifeMixingService")) {
            assertTrue(target.acceptsHostIntent(target.hostPackage, serviceClass, "samsung", 0, false))
        }
    }

    @Test fun honorDoesNotTreatThePrivilegedUiServiceAsAMixingEndpoint() {
        val target = MixingBrand.forFlavor("honor")
        assertFalse(target.acceptsHostIntent("com.hihonor.auto",
            "com.hihonor.auto.ui.CarUiService", "honor", 0, false))
        assertTrue(target.hostServiceClasses.isEmpty())
    }

    @Test fun anUnknownFlavorCannotFallBackToXiaomi() {
        assertThrows(IllegalArgumentException::class.java) { MixingBrand.forFlavor("unknown") }
        assertThrows(IllegalArgumentException::class.java) { MixingBrand.forFlavor("") }
    }
}
