package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

/** 键的规则：**能区分两台车，且缺 `pi` 时与改造前一致**。
 *
 * 现场证据：两台 DiPlay 车机（`DiPlay CS11` / `DiPlay 领克`）`srcvers` 相同、`receiver` 相同，
 * 改造前只看 receiver 时它们共用一条记录，表现为"后配的覆盖先配的 + pin 冲突"。 */
class PairAliasTest {
    private val car="a60450e83f2d"

    /** 核心断言：同一个 receiver 下，两台车（`pi` 不同）必须落到不同的键。 */
    @Test fun theSameReceiverWithDifferentPairingIdsDoesNotCollide(){
        val first=PairAlias.primary(car,"7b2f1c4e-0001-4a11-9c3d-1f0e2b3a4c5d")
        val second=PairAlias.primary(car,"7b2f1c4e-0002-4a11-9c3d-1f0e2b3a4c5d")
        assertNotEquals(first,second)
        assertTrue(first.startsWith(car+"."))
        assertTrue(second.startsWith(car+"."))
        // 同一台车重复连接必须稳定落到同一个键，否则 pin 永远命不中。
        assertEquals(first,PairAlias.primary(car,"7B2F1C4E-0001-4A11-9C3D-1F0E2B3A4C5D"))
    }

    /** `pi` 缺失或不可用时退化为只用 receiver：这正是改造前的键，旧记录与不报 pi 的实现照旧可用。 */
    @Test fun missingOrUnusablePairingIdFallsBackToTheBareReceiver(){
        listOf(null,"","   ","\u0000bad","has space","a".repeat(65)).forEach{raw->
            assertEquals("raw=$raw",car,PairAlias.primary(car,raw))
            assertNull("raw=$raw",PairAlias.pairingId(raw))
        }
        assertEquals(car,PairAlias.fallback(car))
        // 回退键绝不等同于任何带 pi 的键（否则回退会污染精确记录）。
        assertNotEquals(PairAlias.primary(car,"7b2f"),PairAlias.fallback(car))
    }

    /** `pi` 允许的形态：十六进制、UUID、带点/冒号/下划线的标识都原样保留（归一为小写）。 */
    @Test fun observedPairingIdShapesAreKeptVerbatim(){
        assertEquals("db8a1f",PairAlias.pairingId(" DB8A1F "))
        assertEquals("e5f7a68d-7b0f-4305-984b-974f677a150b",
            PairAlias.pairingId("e5f7a68d-7b0f-4305-984b-974f677a150b"))
        assertEquals("car:01_x",PairAlias.pairingId("car:01_x"))
        assertEquals(car+".db8a1f",PairAlias.primary(car,"db8a1f"))
    }
}
