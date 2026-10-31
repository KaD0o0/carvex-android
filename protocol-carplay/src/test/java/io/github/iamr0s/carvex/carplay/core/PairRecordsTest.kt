package io.github.iamr0s.carvex.carplay.core

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PairRecordsTest {
    private class Memory : PairRecordStorage {
        val data = mutableMapOf<String, ByteArray>()
        override fun read(key: String) = data[key]
        override fun write(key: String, value: ByteArray?) { if (value == null) data.remove(key) else data[key] = value }
    }
    private val owner = PairIdentity("synthetic-owner", ByteArray(32) { 1 })
    private val peer = PeerIdentity("synthetic-peer", ByteArray(32) { 2 })
    private val binding = SecurityBinding(1, peer.id, 1)
    private fun result(layer: AuthLayer, synthetic: Boolean = false, context: SecurityBinding = binding) =
        AuthTrustResult(layer, "synthetic-policy", 1, synthetic, context)
    private fun activate(r: PairRecords, key: String, p: PeerIdentity = peer) {
        r.stage(key, "a", p)
        r.commit(key,"a",binding,result(AuthLayer.IAP2_AA),result(AuthLayer.AIRPLAY_SAP))
    }
    @Test fun keyBindsOwnerRawIdentifierNamespaceAndSchema() {
        val r = PairRecords(Memory())
        val key = r.key(owner,peer.identifier,"test",1)
        assertNotEquals(key,r.key(owner.copy(seed=ByteArray(32){3}),peer.identifier,"test",1))
        assertNotEquals(r.key(owner,byteArrayOf(0xc0.toByte()),"test",1),r.key(owner,byteArrayOf(0xc1.toByte()),"test",1))
        assertNotEquals(key,r.key(owner,peer.identifier,"other",1))
        assertNotEquals(key,r.key(owner,peer.identifier,"test",2))
    }
    @Test fun pendingIsOnlyVisibleToItsTransactionAndCrashDoesNotActivateIt() {
        val m=Memory(); val r=PairRecords(m)
        r.stage("k","a",peer)
        assertEquals(PairRecordStatus.PENDING_SAP,r.lookup("k","a").status)
        assertEquals(PairRecordStatus.QUARANTINED,PairRecords(m).lookup("k").status)
        assertThrows(SecurityFailure::class.java) { r.stage("k","b",peer) }
        r.rollback("k","a")
        assertEquals(PairRecordStatus.ABSENT,r.lookup("k").status)
    }
    @Test fun onlyBothProductionLayersFromCurrentEpochCanCommit() {
        val r=PairRecords(Memory());r.stage("k","a",peer)
        for ((aa,sap) in listOf(result(AuthLayer.IAP2_AA,true) to result(AuthLayer.AIRPLAY_SAP),
            result(AuthLayer.IAP2_AA) to result(AuthLayer.AIRPLAY_SAP,true),
            result(AuthLayer.IAP2_AA) to result(AuthLayer.AIRPLAY_SAP,context=binding.copy(epoch=2)))) {
            assertThrows(SecurityFailure::class.java) { r.commit("k","a",binding,aa,sap) }
            assertEquals(PairRecordStatus.PENDING_SAP,r.lookup("k","a").status)
        }
        r.commit("k","a",binding,result(AuthLayer.IAP2_AA),result(AuthLayer.AIRPLAY_SAP))
        assertEquals(PairRecordStatus.ACTIVE,r.lookup("k").status)
    }
    @Test fun rollbackKeepsOldActiveAndConflictingKeyIsQuarantined() {
        val r=PairRecords(Memory()); activate(r,"k")
        r.stage("k","b",peer);r.rollback("k","b")
        assertArrayEquals(peer.public,r.lookup("k").peer!!.public)
        assertEquals(SecurityReason.PEER_BINDING_CONFLICT,assertThrows(SecurityFailure::class.java) {
            r.stage("k","c",peer.copy(public=ByteArray(32){9}))
        }.diagnostic.localReason)
        assertEquals(PairRecordStatus.QUARANTINED,r.lookup("k").status)
    }
    @Test fun corruptAndIoAreNeverAbsence() {
        val m=Memory();m.data["k"]=byteArrayOf(0,1)
        assertEquals(SecurityReason.PAIR_STORE_CORRUPT,assertThrows(SecurityFailure::class.java) { PairRecords(m).lookup("k") }.diagnostic.localReason)
        val broken=object:PairRecordStorage {
            override fun read(key:String):ByteArray? = throw IOException("synthetic storage unavailable")
            override fun write(key:String,value:ByteArray?) { error("must not write") }
        }
        assertEquals(SecurityReason.PAIR_STORE_IO_ERROR,assertThrows(SecurityFailure::class.java) { PairRecords(broken).lookup("k") }.diagnostic.localReason)
    }
}
