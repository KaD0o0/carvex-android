package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class ExperimentalHapV11Test {
    private val receiver="aabbccddeeff"
    private val pi="Pair-ID-Example"
    private val identifier=pi.toByteArray()
    private val owner=PairIdentity("controller",ByteArray(32){1})
    private val peer=PeerIdentity(pi,ByteArray(32){2})
    private class Memory:PairRecordStorage {
        val values=mutableMapOf<String,ByteArray>()
        override fun read(key:String)=values[key]
        override fun write(key:String,value:ByteArray?){if(value==null)values.remove(key)else values[key]=value}
    }
    private fun reason(reason:SecurityReason,block:()->Unit){
        assertEquals(reason,assertThrows(SecurityFailure::class.java,block).diagnostic.localReason)
    }
    @Test fun deviceIdDoesNotHaveToEqualPairingIdentifier(){
        assertArrayEquals(identifier,PairingIdentityBinding.identifier("AA:BB:CC:DD:EE:FF",receiver,pi))
        // 固件对 /info 与 Bonjour 的 deviceID 冲突是"保留原值并继续"，不再中止；
        // 只把冲突记进 lastDeviceIdConflict 供调用方写日志。
        assertEquals(false,PairingIdentityBinding.lastDeviceIdConflict)
        assertArrayEquals(identifier,PairingIdentityBinding.identifier(receiver,"001122334455",pi))
        assertTrue(PairingIdentityBinding.lastDeviceIdConflict)
        reason(SecurityReason.PAIR_IDENTIFIER_MISSING){PairingIdentityBinding.identifier(receiver,receiver,null)}
        reason(SecurityReason.PEER_BINDING_CONFLICT){PairingIdentityBinding.requirePeer(identifier,null,peer.copy(identifier=pi.lowercase().toByteArray()))}
    }
    @Test fun optionalIdentifierAllows4301OnlyPathWithoutTreatingDeviceIdAsHapId(){
        assertNull(PairingIdentityBinding.optionalIdentifier(receiver,receiver,null))
        assertArrayEquals(identifier,PairingIdentityBinding.optionalIdentifier(receiver,receiver,pi))
    }
    @Test fun oldAliasIsVerifiedThenMigratedWithoutProductionActivation(){
        val memory=Memory()
        val alias="peer-"+PairAlias.primary(receiver,pi)
        val raw=Plist.encode(mapOf("id" to peer.id,"public" to peer.public))
        memory.values[alias]=raw
        val records=ExperimentalHapRecords(memory)
        var verified=false
        val shared=ExperimentalHapV11.authenticate(identifier,peer.public,records.load(owner,receiver,pi),
            setup={fail("existing peer must not run setup");peer},
            verify={assertArrayEquals(peer.public,it.public);verified=true;byteArrayOf(9)},
            saveVerified={assertTrue(verified);records.saveVerified(owner,receiver,pi,it)})
        assertArrayEquals(byteArrayOf(9),shared)
        assertArrayEquals(raw,memory.values[alias])
        assertEquals(2,memory.values.size)
        assertTrue(memory.values.keys.any{it.startsWith("experiment-hap-v11-")})
        assertFalse(memory.values.keys.any{it.startsWith("record-v2-")})
        assertArrayEquals(peer.public,records.load(owner,receiver,pi)!!.public)
    }
    @Test fun freshSetupUsesV11InputAndSavesOnlyAfterVerify(){
        var saved=false
        var heldCode:ByteArray?=null
        ExperimentalHapV11.authenticate(identifier,peer.public,null,
            setup={assertArrayEquals("3939".toByteArray(),it);heldCode=it;peer},
            verify={assertFalse(saved);ByteArray(32){3}},saveVerified={saved=true})
        assertTrue(saved)
        assertTrue(heldCode!!.all{it==0.toByte()})
    }
    @Test fun failedVerifyNeverSavesOrRetriesSetup(){
        var setups=0
        reason(SecurityReason.PAIR_SIGNATURE_INVALID){ExperimentalHapV11.authenticate(identifier,peer.public,null,
            setup={setups++;peer},verify={securityFailure(SecurityReason.PAIR_SIGNATURE_INVALID)},
            saveVerified={fail("failed verify must not persist")})}
        assertEquals(1,setups)
    }
    @Test fun changedPinOrIdentifierRejectsWithoutOverwrite(){
        val memory=Memory()
        val records=ExperimentalHapRecords(memory)
        records.saveVerified(owner,receiver,pi,peer)
        val before=memory.values.mapValues{it.value.copyOf()}
        reason(SecurityReason.PEER_BINDING_CONFLICT){records.saveVerified(owner,receiver,pi,peer.copy(public=ByteArray(32){7}))}
        reason(SecurityReason.PEER_BINDING_CONFLICT){ExperimentalHapV11.authenticate(identifier,ByteArray(32){7},peer,
            setup={fail("no setup");peer},verify={fail("no verify after pin conflict");byteArrayOf()},saveVerified={fail("no save")})}
        assertEquals(before.keys,memory.values.keys)
        before.forEach{(key,value)->assertArrayEquals(value,memory.values[key])}
    }
    @Test fun unrelatedFallbackIsNotReusedAndCorruptExactAliasIsNotRepairedSilently(){
        val memory=Memory()
        val records=ExperimentalHapRecords(memory)
        memory.values["peer-$receiver"]=Plist.encode(mapOf("id" to "other-pair-id","public" to peer.public))
        assertNull(records.load(owner,receiver,pi))
        val exact="peer-"+PairAlias.primary(receiver,pi)
        memory.values[exact]=byteArrayOf(1,2,3)
        reason(SecurityReason.PAIR_STORE_CORRUPT){records.load(owner,receiver,pi)}
        assertArrayEquals(byteArrayOf(1,2,3),memory.values[exact])
    }
    @Test fun migratedRecordIsBoundToOriginalController(){
        val memory=Memory()
        val records=ExperimentalHapRecords(memory)
        records.saveVerified(owner,receiver,pi,peer)
        assertNull(records.load(owner.copy(seed=ByteArray(32){8}),receiver,pi))
        assertNull(records.load(owner,"001122334455",pi))
    }

    @Test fun receiverFallbackStoresRealHapIdentifierAndRejectsReplacement(){
        val memory=Memory();val records=ExperimentalHapRecords(memory)
        records.saveFallback(owner,receiver,peer)
        val loaded=records.loadFallback(owner,receiver)!!
        assertArrayEquals(peer.identifier,loaded.identifier);assertArrayEquals(peer.public,loaded.public)
        reason(SecurityReason.PEER_BINDING_CONFLICT){
            records.saveFallback(owner,receiver,peer.copy(public=ByteArray(32){7}))
        }
        val retained=records.loadFallback(owner,receiver)!!
        assertArrayEquals(peer.identifier,retained.identifier);assertArrayEquals(peer.public,retained.public)
    }
}
