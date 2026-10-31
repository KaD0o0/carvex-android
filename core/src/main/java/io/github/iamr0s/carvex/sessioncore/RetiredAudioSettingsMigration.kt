package io.github.iamr0s.carvex.sessioncore

import android.content.SharedPreferences

/** Remove only retired diagnostic overrides; safe on fresh installs and repeat upgrades. */
internal object RetiredAudioSettingsMigration {
    fun removeFrom(editor:SharedPreferences.Editor):SharedPreferences.Editor = editor
        .remove("speechOutputEnabled")
        .remove("speechNoRed")
        .remove("speechPcmOutput")
}
