package io.github.iamr0s.carvex.carplay
import io.github.iamr0s.carvex.sessioncore.*

import android.content.Context
import android.security.keystore.*
import android.util.Base64
import io.github.iamr0s.carvex.carplay.core.*
import java.security.KeyStore
import java.util.UUID
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec

class PairStore(context:Context) {
    private val prefs=context.getSharedPreferences("pairing",Context.MODE_PRIVATE)
    private fun key():java.security.Key {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        if(!ks.containsAlias("carwith-pair-store")){
            if(prefs.all.isNotEmpty())securityFailure(SecurityReason.LOCAL_IDENTITY_LOST,phase="keystore")
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
                init(KeyGenParameterSpec.Builder("carwith-pair-store",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        };return ks.getKey("carwith-pair-store",null)
    }
    private fun save(name:String,data:ByteArray):Unit = try {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key())}
        val b=cipher.iv+cipher.doFinal(data)
        if(!prefs.edit().putString(name,Base64.encodeToString(b,Base64.NO_WRAP)).commit())
            securityFailure(SecurityReason.PAIR_STORE_IO_ERROR,phase="record-write")
        Unit
    } catch(t:SecurityFailure){throw t}
      catch(t:Exception){throw SecurityFailure(SecurityDiagnostic("AirPlay-pair","record-write",SecurityReason.PAIR_STORE_IO_ERROR),cause=t)}
    private fun read(name:String):ByteArray? = try {
        val s=prefs.getString(name,null)
        if(s==null)null else {
            val b=Base64.decode(s,Base64.NO_WRAP);require(b.size>=28)
            Cipher.getInstance("AES/GCM/NoPadding").run{init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,b.copyOfRange(0,12)));doFinal(b.copyOfRange(12,b.size))}
        }
    } catch(t:SecurityFailure){throw t}
      catch(t:KeyPermanentlyInvalidatedException){throw SecurityFailure(SecurityDiagnostic("AirPlay-pair","keystore",SecurityReason.LOCAL_IDENTITY_LOST),cause=t)}
      catch(t:java.security.KeyStoreException){throw SecurityFailure(SecurityDiagnostic("AirPlay-pair","keystore",SecurityReason.PAIR_STORE_IO_ERROR),cause=t)}
      catch(t:java.security.UnrecoverableKeyException){throw SecurityFailure(SecurityDiagnostic("AirPlay-pair","keystore",SecurityReason.LOCAL_IDENTITY_LOST),cause=t)}
      catch(t:java.io.IOException){throw SecurityFailure(SecurityDiagnostic("AirPlay-pair","record-read",SecurityReason.PAIR_STORE_IO_ERROR),cause=t)}
      catch(t:Exception){throw SecurityFailure(SecurityDiagnostic("AirPlay-pair","record-read",SecurityReason.PAIR_STORE_CORRUPT),cause=t)}
    fun identity():PairIdentity {
        val raw=read("identity")
        if(raw!=null)try{val p=Plist.dictionary(raw);return PairIdentity(p["id"] as String,p["seed"] as ByteArray)}
            catch(t:Exception){throw SecurityFailure(SecurityDiagnostic("AirPlay-pair","identity",SecurityReason.PAIR_STORE_CORRUPT),cause=t)}
        if(prefs.all.keys.any{it.startsWith("peer-")||it.startsWith("record-v2-")||it.startsWith("experiment-hap-v11-")})
            securityFailure(SecurityReason.LOCAL_IDENTITY_LOST,phase="identity")
        val i=PairIdentity(UUID.randomUUID().toString(),Crypto.random(32));save("identity",Plist.encode(mapOf("id" to i.id,"seed" to i.seed)));return i
    }
    val experimental = ExperimentalHapRecords(object : PairRecordStorage {
        override fun read(key:String)=this@PairStore.read(key)
        override fun write(key:String,value:ByteArray?) {
            require(value!=null)
            save(key,value)
        }
    })
    // Legacy aliases remain ineligible for production ACTIVE records. The explicit
    // experimental path above can verify and migrate them without changing formal trust.
    fun recordKey(owner: PairIdentity, identifier: ByteArray): String {
        if (identifier.isEmpty()) securityFailure(SecurityReason.PAIR_IDENTIFIER_MISSING)
        val legacy = prefs.all.keys.filter { it.startsWith("peer-") }
        legacy.forEach { name ->
            val previous = read(name)?.let { Plist.dictionary(it) } ?: securityFailure(SecurityReason.PAIR_STORE_CORRUPT)
            val raw = previous["identifier"] as? ByteArray ?: (previous["id"] as? String)?.toByteArray(Charsets.UTF_8)
                ?: securityFailure(SecurityReason.PAIR_STORE_CORRUPT)
            if (raw.contentEquals(identifier)) securityFailure(SecurityReason.PAIR_QUARANTINED)
        }
        return records.key(owner, identifier, InternalSecurityProfiles.referenceId, InternalSecurityProfiles.pairingSchemaRevision)
    }
    val records = PairRecords(object : PairRecordStorage {
        override fun read(key: String) = this@PairStore.read("record-v2-" + key)
        override fun write(key: String, value: ByteArray?) {
            if (value != null) save("record-v2-" + key, value)
            else if (!prefs.edit().remove("record-v2-" + key).commit()) securityFailure(SecurityReason.PAIR_STORE_IO_ERROR)
        }
    })
}
