package io.github.iamr0s.carvex.ui

import io.github.iamr0s.carvex.sessioncore.BondedDevices
import io.github.iamr0s.carvex.sessioncore.DeviceHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首页两段列表的规则：不重复 + 排序稳定。这两条错了界面看着"能用"，但会误导用户。 */
class DeviceListModelTest {
    private fun h(name: String, addr: String, at: Long) = DeviceHistory.Entry(name, addr, at)

    @Test fun historySortsByMostRecentFirst() {
        val s = DeviceListModel.build(
            listOf(h("旧车", "AA:00:00:00:00:01", 100), h("新车", "AA:00:00:00:00:02", 300)),
            emptyList()
        )
        assertEquals(listOf("新车", "旧车"), s.history.map { it.name })
    }

    @Test fun addressInHistoryIsNotRepeatedInPairedSection() {
        val s = DeviceListModel.build(
            history = listOf(h("我的车机", "AA:BB:CC:DD:EE:01", 500)),
            paired = listOf(BondedDevices.Device("我的车机","AA:BB:CC:DD:EE:01",BondedDevices.Kind.CAR), BondedDevices.Device("另一台","AA:BB:CC:DD:EE:02",BondedDevices.Kind.CAR))
        )
        assertEquals(listOf("我的车机"), s.history.map { it.name })
        assertEquals(listOf("另一台"), s.paired.map { it.name })
        assertEquals("两段合计不得出现重复设备", 2, s.total)
    }

    @Test fun dedupIgnoresAddressCase() {
        // 蓝牙地址大小写在不同来源上不一致，按地址去重必须归一化，否则会重复出现。
        val s = DeviceListModel.build(
            history = listOf(h("车机", "aa:bb:cc:dd:ee:01", 500)),
            paired = listOf(BondedDevices.Device("车机","AA:BB:CC:DD:EE:01",BondedDevices.Kind.CAR))
        )
        assertTrue("大小写不同不应被当成两台设备", s.paired.isEmpty())
        assertEquals(1, s.total)
    }

    @Test fun duplicateWithinHistoryCollapsesToNewest() {
        val s = DeviceListModel.build(
            listOf(h("旧名", "AA:00:00:00:00:01", 100), h("新名", "AA:00:00:00:00:01", 900)),
            emptyList()
        )
        assertEquals(1, s.history.size)
        assertEquals("新名", s.history.first().name)
        assertEquals(900L, s.history.first().lastConnectedAt)
    }

    @Test fun pairedSectionKeepsInputOrderWhenNoHistory() {
        val s = DeviceListModel.build(emptyList(), listOf(BondedDevices.Device("A","AA:00:00:00:00:01",BondedDevices.Kind.CAR), BondedDevices.Device("B","AA:00:00:00:00:02",BondedDevices.Kind.CAR)))
        assertEquals(listOf("A", "B"), s.paired.map { it.name })
        assertTrue(s.history.isEmpty())
        assertFalse(s.isEmpty)
    }

    @Test fun bothEmptyReportsEmpty() {
        val s = DeviceListModel.build(emptyList(), emptyList())
        assertTrue(s.isEmpty)
        assertEquals(0, s.total)
    }
}
