package io.github.iamr0s.carvex.carplay.core

/** 24A446 APSFeatures is a variable-length, LSB-first byte buffer, not a ULong. */
class AirPlayFeatureSet(bytes: ByteArray) {
    private val data = bytes.copyOf()
    val bytes: ByteArray get() = data.copyOf()
    val byteCount: Int get() = data.size

    fun has(bit: Int): Boolean = bit >= 0 && bit / 8 < data.size &&
        (data[bit / 8].toInt() and (1 shl (bit % 8))) != 0

    /** Compatibility view only; high features must be queried with [has]. */
    val legacyFlags: ULong
        get() = (0 until minOf(8, data.size)).fold(0UL) { flags, index ->
            flags or ((data[index].toInt() and 0xff).toULong() shl (index * 8))
        }

    override fun equals(other: Any?): Boolean = other is AirPlayFeatureSet && data.contentEquals(other.data)
    override fun hashCode(): Int = data.contentHashCode()
    override fun toString(): String = "AirPlayFeatureSet(bytes=${data.size})"

    companion object {
        fun fromLegacyFlags(flags: ULong) = AirPlayFeatureSet(ByteArray(8) { (flags shr (it * 8)).toByte() })
    }
}

enum class AirPlayFeatureSource { MISSING, LEGACY_FLAGS, BONJOUR_FEX, ENDPOINT_INFO_FEATURES_EX }
enum class AirPlayFeatureProblem { MALFORMED_BASE64, MALFORMED_LENGTH, UNSUPPORTED_ENCODING, MALFORMED_LEGACY_FLAGS }

/** An explicit missing/malformed observation must never fall back to an unrelated low-64 mask. */
data class AirPlayFeatureEvidence(
    val source: AirPlayFeatureSource,
    val set: AirPlayFeatureSet?,
    val problem: AirPlayFeatureProblem? = null,
) {
    init {
        require(problem == null || set == null)
        require(if (source == AirPlayFeatureSource.MISSING) set == null && problem == null else set != null || problem != null)
    }
    val malformed: Boolean get() = problem != null
    fun has(bit: Int): Boolean? = set?.has(bit)
    fun diagnostic(): String = "source=$source bytes=${set?.byteCount} problem=$problem"

    companion object {
        // Local resource bound, not an asserted Apple wire-size limit.
        const val MAX_EXTENDED_TEXT_LENGTH = 1024

        fun fromLegacyFlags(flags: ULong?): AirPlayFeatureEvidence = if (flags == null)
            AirPlayFeatureEvidence(AirPlayFeatureSource.MISSING, null)
        else AirPlayFeatureEvidence(AirPlayFeatureSource.LEGACY_FLAGS, AirPlayFeatureSet.fromLegacyFlags(flags))

        /** APTransport uses TXT "fex" first, "features" only when fex is absent. */
        fun fromBonjour(fex: String?, legacyFeatures: String?): AirPlayFeatureEvidence {
            if (fex != null) return fromExtended(fex, AirPlayFeatureSource.BONJOUR_FEX)
            if (legacyFeatures == null) return fromLegacyFlags(null)
            val flags = ReceiverIdentity.features(legacyFeatures)
                ?: return AirPlayFeatureEvidence(AirPlayFeatureSource.LEGACY_FLAGS, null,
                    AirPlayFeatureProblem.MALFORMED_LEGACY_FLAGS)
            return fromLegacyFlags(flags)
        }

        /**
         * APSFeaturesCreateFromStringRepresentation -> ASCII C string -> Base64DecodeCopy.
         * CoreUtils 0x18be447e0 accepts both alphabets, ASCII whitespace and missing padding;
         * first '=' ends decoding, 1 residual sextet is ignored, and unused bits are not checked.
         * This deliberately does not substitute java.util.Base64's stricter grammar.
         */
        fun fromExtended(value: String, source: AirPlayFeatureSource): AirPlayFeatureEvidence {
            require(source == AirPlayFeatureSource.BONJOUR_FEX || source == AirPlayFeatureSource.ENDPOINT_INFO_FEATURES_EX)
            fun failure(problem: AirPlayFeatureProblem) = AirPlayFeatureEvidence(source, null, problem)
            if (value.length > MAX_EXTENDED_TEXT_LENGTH) return failure(AirPlayFeatureProblem.MALFORMED_LENGTH)
            // CFStringGetCString(..., kCFStringEncodingASCII) precedes strlen/Base64Decode.
            if (value.any { it.code > 0x7f }) return failure(AirPlayFeatureProblem.UNSUPPORTED_ENCODING)
            val output = ByteArray((value.length + 3) / 4 * 3)
            var written = 0
            var count = 0
            var bits = 0
            for (ch in value) {
                if (ch == '\u0000' || ch == '=') break
                if (ch == ' ' || ch in '\t'..'\r') continue
                val digit = when (ch) {
                    in 'A'..'Z' -> ch - 'A'
                    in 'a'..'z' -> ch - 'a' + 26
                    in '0'..'9' -> ch - '0' + 52
                    '+', '-' -> 62
                    '/', '_' -> 63
                    else -> return failure(AirPlayFeatureProblem.MALFORMED_BASE64)
                }
                bits = (bits shl 6) or digit
                if (++count == 4) {
                    output[written++] = (bits shr 16).toByte()
                    output[written++] = (bits shr 8).toByte()
                    output[written++] = bits.toByte()
                    count = 0
                    bits = 0
                }
            }
            if (count == 2) output[written++] = (bits shr 4).toByte()
            else if (count == 3) {
                output[written++] = (bits shr 10).toByte()
                output[written++] = (bits shr 2).toByte()
            }
            return AirPlayFeatureEvidence(source, AirPlayFeatureSet(output.copyOf(written)))
        }
    }
}
