package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class AirPlayFeaturesTest {
    /** gap-closure §2.1 的固件观测值：低 32 位 = 0x44440B80，逐位对到下面这组名字。
     *  这条断言的意义是"位号与名字的对应关系可回归" —— 与 catplay-cross-reference §6 的位表逐位互证。 */
    @Test fun firmwareMaskDecodesToTheNamesTheSamplesImply(){
        val capabilities=AirPlayCapabilities(0x44440B80UL)
        assertEquals(listOf("SCREEN","ROTATE","AUDIO","REDUNDANT_AUDIO","AUDIO_PCM","AUDIO_UNENCRYPTED",
            "AUDIO_AES_128_MFI_SAP_V1","UNIFIED_BONJOUR"),capabilities.names())
        listOf(AirPlayFeature.SCREEN,AirPlayFeature.AUDIO,AirPlayFeature.AUDIO_PCM,AirPlayFeature.UNIFIED_BONJOUR)
            .forEach{assertTrue(it.name,capabilities.has(it))}
        listOf(AirPlayFeature.CAR,AirPlayFeature.HK_PAIRING_AND_ENCRYPT,AirPlayFeature.SUPPORTS_SCREEN_MULTI_CODEC)
            .forEach{assertFalse(it.name,capabilities.has(it))}
        // 老平台实际支持 AAC-LC/AAC-ELD，却没有置这两个位（它只置了 ALAC/PCM）。
        // 所以 features 的音频编码位**不能**当作"支持哪些编码"的判据，判决权在 /info 的 audioFormats。
        assertFalse(capabilities.has(AirPlayFeature.AUDIO_AAC_LC))
        assertFalse(capabilities.has(AirPlayFeature.AUDIO_AAC_ELD))
    }

    /** 拿不到掩码时，能力是**未知**而不是"没有"：文档 §9.8 要求此时两条路都保留。 */
    @Test fun missingMaskIsUnknownNotAbsent(){
        val capabilities=AirPlayCapabilities.unknown
        assertNull(capabilities.state(AirPlayFeature.AUDIO_AAC_ELD))
        assertFalse(capabilities.has(AirPlayFeature.AUDIO_AAC_ELD))
        assertTrue(capabilities.names().isEmpty())
        assertEquals(capabilities,AirPlayCapabilities.parse(null))
    }

    @Test fun unknownBitsAreKeptByNumberInsteadOfBeingNamed(){
        val capabilities=AirPlayCapabilities((1UL shl 7) or (1UL shl 5))
        assertEquals(listOf("bit5","SCREEN"),capabilities.names())
        assertNull(AirPlayFeature.at(5))
        assertEquals(AirPlayFeature.CARPLAY_CONTROL,AirPlayFeature.at(37))
    }

    @Test fun parseAcceptsBothTxtShapes(){
        assertEquals(0x44440B80UL,AirPlayCapabilities.parse("0x44440B80").features)
        assertEquals(1UL,AirPlayCapabilities.parse("0x1,0x0").features)
        assertEquals(1UL shl 32,AirPlayCapabilities.parse("0x0,0x1").features)
        assertNull(AirPlayCapabilities.parse("not-hex").features)
    }

    /** T3 的观测位（含 37/38）判为 AirPlay 2；老平台的 protovers / protocolVersion=1.0 判为 AirPlay 1。 */
    @Test fun observationSetsDecideTheGeneration(){
        val t3=AirPlayGenerationEvidence(features=AirPlayCapabilities(0x44440B80UL or (1UL shl 37) or (1UL shl 38)))
        assertEquals(AirPlayGeneration.V2,AirPlayGenerations.estimate(t3))
        assertEquals(AirPlayGeneration.V1,AirPlayGenerations.estimate(AirPlayGenerationEvidence(protocolVersion="1.0")))
        assertEquals(AirPlayGeneration.V2,AirPlayGenerations.estimate(AirPlayGenerationEvidence(services=setOf("_raop._tcp."))))
        assertEquals(AirPlayGeneration.V2,AirPlayGenerations.estimate(AirPlayGenerationEvidence(services=setOf("_mfi-config._tcp."))))
        assertEquals(AirPlayGeneration.UNKNOWN,AirPlayGenerations.estimate(AirPlayGenerationEvidence()))
    }

    /** 能力探测压过静态特征：/pair-verify 能通就是 AirPlay 2，通了不成就退回 AirPlay 1 —— 两个方向都要。 */
    @Test fun theCapabilityProbeOutranksEveryStaticClue(){
        val looksLikeV1=AirPlayGenerationEvidence(protocolVersion="1.0",pairVerifyAvailable=true)
        assertEquals(AirPlayGeneration.V2,AirPlayGenerations.estimate(looksLikeV1))
        val looksLikeV2=AirPlayGenerationEvidence(services=setOf("_raop._tcp.","_hap._tcp."),pairVerifyAvailable=false)
        assertEquals(AirPlayGeneration.V1,AirPlayGenerations.estimate(looksLikeV2))
    }

    @Test fun authenticationSelectionNeverProvidesAnAutomaticDowngrade(){
        assertTrue(PairStrategies.order(AirPlayGeneration.V1).isEmpty())
        assertTrue(PairStrategies.order(AirPlayGeneration.V2).isEmpty())
        assertTrue(PairStrategies.order(AirPlayGeneration.UNKNOWN).isEmpty())
    }

    /** `_carplay-ctrl` 只是实现线索（GM/Cinemo），单独出现也按 AirPlay 2 准备，但必须能被认出来。 */
    @Test fun cinemoServicesAreRecognisedAsAClueNotARequirement(){
        assertTrue(AirPlayGenerations.cinemoStyle(setOf("_carplay-ctrl._tcp.")))
        assertTrue(AirPlayGenerations.cinemoStyle(setOf("_mfi-config._tcp.")))
        assertFalse(AirPlayGenerations.cinemoStyle(setOf("_airplay._tcp.","_raop._tcp.")))
    }
}
