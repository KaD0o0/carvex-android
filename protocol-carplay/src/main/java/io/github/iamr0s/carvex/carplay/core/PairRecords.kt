package io.github.iamr0s.carvex.carplay.core
import java.io.IOException
import java.security.MessageDigest

/** PairStore encrypts these opaque values and atomically replaces one preference entry. */
interface PairRecordStorage {
    fun read(key: String): ByteArray?
    fun write(key: String, value: ByteArray?)
}
data class PairLookup(val status: PairRecordStatus, val peer: PeerIdentity? = null)

class PairRecords(private val storage: PairRecordStorage) {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun key(owner: PairIdentity, identifier: ByteArray, namespace: String, schemaRevision: Int): String {
        require(identifier.size in 1..1024 && namespace.isNotBlank() && schemaRevision > 0)
        val encoded = Plist.encode(mapOf("owner" to hash(Crypto.public(owner.seed)), "identifier" to identifier,
            "namespace" to namespace, "schemaRevision" to schemaRevision))
        return hash(encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun load(key: String): Map<String, Any?>? = try {
        storage.read(key)?.let { Plist.dictionary(it) }
    } catch (e: SecurityFailure) { throw e }
      catch (e: IOException) { securityFailure(SecurityReason.PAIR_STORE_IO_ERROR, phase = "record-read") }
      catch (e: Exception) { securityFailure(SecurityReason.PAIR_STORE_CORRUPT, phase = "record-read") }
    private fun save(key: String, value: Map<String, Any?>?) {
        try { storage.write(key, value?.let { Plist.encode(it) }) }
        catch (e: SecurityFailure) { throw e }
        catch (e: Exception) { securityFailure(SecurityReason.PAIR_STORE_IO_ERROR, phase = "record-write") }
    }
    private fun peer(value: Any?): PeerIdentity? {
        if (value == null) return null
        try {
            val p = value as Map<*, *>
            val id = p["identifier"] as ByteArray
            return PeerIdentity(id.toString(Charsets.UTF_8), p["public"] as ByteArray, id)
        } catch (e: Exception) { securityFailure(SecurityReason.PAIR_STORE_CORRUPT, phase = "record-decode") }
    }
    private fun encoded(p: PeerIdentity) = mapOf("identifier" to p.identifier, "public" to p.public)
    @Synchronized fun lookup(key: String, transaction: String? = null): PairLookup {
        val entry = load(key) ?: return PairLookup(PairRecordStatus.ABSENT)
        if (entry["quarantined"] == true) return PairLookup(PairRecordStatus.QUARANTINED)
        val active = peer(entry["active"])
        if (active != null) return PairLookup(PairRecordStatus.ACTIVE, active)
        if (transaction != null && entry["transaction"] == transaction)
            return PairLookup(PairRecordStatus.PENDING_SAP, peer(entry["pending"]) ?: securityFailure(SecurityReason.PAIR_STORE_CORRUPT))
        return PairLookup(PairRecordStatus.QUARANTINED) // Interrupted PENDING is never reusable as ACTIVE.
    }
    @Synchronized fun stage(key: String, transaction: String, candidate: PeerIdentity) {
        require(transaction.isNotBlank())
        val old = load(key).orEmpty()
        if (old["quarantined"] == true) securityFailure(SecurityReason.PAIR_QUARANTINED)
        val active = peer(old["active"])
        if (active != null && (!active.identifier.contentEquals(candidate.identifier) || !active.public.contentEquals(candidate.public))) {
            save(key, old + ("quarantined" to true))
            securityFailure(SecurityReason.PEER_BINDING_CONFLICT)
        }
        // An unowned pending transaction is evidence to inspect, not a missing record.
        if (old["pending"] != null && old["transaction"] != transaction) securityFailure(SecurityReason.PAIR_QUARANTINED)
        save(key, old + mapOf("pending" to encoded(candidate), "transaction" to transaction, "state" to "PENDING_SAP"))
    }
    @Synchronized fun commit(key: String, transaction: String, binding: SecurityBinding, aa: AuthTrustResult, sap: AuthTrustResult) {
        AuthTrustVerifier.requireProductionPair(aa, sap, binding)
        val old = load(key) ?: securityFailure(SecurityReason.PAIR_STORE_CORRUPT)
        if (old["transaction"] != transaction || old["quarantined"] == true) securityFailure(SecurityReason.STALE_CONTEXT)
        val pending = peer(old["pending"]) ?: securityFailure(SecurityReason.PAIR_STORE_CORRUPT)
        val version = (old["commitVersion"] as? Number)?.toLong() ?: 0L
        if (version < 0L || version == Long.MAX_VALUE) securityFailure(SecurityReason.PAIR_STORE_CORRUPT)
        save(key, mapOf("active" to encoded(pending), "state" to "ACTIVE", "commitVersion" to version + 1L,
            "aaPolicy" to aa.policyId, "aaRevision" to aa.revision, "sapPolicy" to sap.policyId, "sapRevision" to sap.revision))
    }
    @Synchronized fun rollback(key: String, transaction: String) {
        val old = load(key) ?: return
        if (old["transaction"] != transaction) return
        val active = old["active"]
        save(key, if (active == null) null else old.filterKeys { it !in setOf("pending", "transaction") } + ("state" to "ACTIVE"))
    }
}
