package io.github.iamr0s.carvex.carplay

import io.github.iamr0s.carvex.sessioncore.MainAudioOwnership
import io.github.iamr0s.carvex.carplay.core.ScreenResourceModes

/** Wire entity 1 is Phone; entity 2 is ACC. receiverDeclared expects ACC ownership. */
internal fun MainAudioOwnership.receiverDeclaredModes(state:ScreenResourceModes.State) {
    receiverDeclared(state.audioOwner==2)
}
