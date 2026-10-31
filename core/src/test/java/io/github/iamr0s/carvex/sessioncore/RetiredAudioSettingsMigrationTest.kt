package io.github.iamr0s.carvex.sessioncore

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class RetiredAudioSettingsMigrationTest {
    private val keys=listOf("speechOutputEnabled","speechNoRed","speechPcmOutput")
    /** Test the actual editor calls, without loading Android's runtime stubs. */
    private class Store(initial:Map<String,Any>) {
        val values=initial.toMutableMap()
        private val removed=mutableSetOf<String>()
        val editor=Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)){proxy,method,args->
            when(method.name){
                "remove"->{removed+=args!![0] as String;proxy}
                "apply"->{removed.forEach(values::remove);removed.clear();null}
                else->error("Migration must not clear/write unrelated preferences: "+method.name)
            }
        } as SharedPreferences.Editor
    }
    @Test fun allOldSwitchCombinationsAreRemovedWithoutChangingOtherSettings() {
        val unrelated=mapOf<String,Any>("target" to "synthetic-car","phoneMac" to "synthetic-mac",
            "dpi" to 200,"experiment" to true,"autoConnect" to false,"deviceName" to "Test phone")
        for(output in listOf(false,true))for(noRed in listOf(false,true))for(pcm in listOf(false,true)){
            val store=Store(unrelated+mapOf(keys[0] to output,keys[1] to noRed,keys[2] to pcm))
            val edited=RetiredAudioSettingsMigration.removeFrom(store.editor)
            assertSame(store.editor,edited);edited.apply()
            assertEquals(unrelated,store.values)
        }
    }
    @Test fun freshInstallAndRepeatedMigrationAreIdempotent() {
        for(initial in listOf(emptyMap(),mapOf<String,Any>("dpi" to 160))){
            val store=Store(initial)
            repeat(3){RetiredAudioSettingsMigration.removeFrom(store.editor).apply()}
            assertEquals(initial,store.values)
        }
    }
    @Test fun partialOrWrongTypedLegacyValuesAreRemovedWithoutReadingThem() {
        for(key in keys){
            val store=Store(mapOf(key to "obsolete-value","bindNetwork" to true))
            RetiredAudioSettingsMigration.removeFrom(store.editor).apply()
            assertEquals(mapOf("bindNetwork" to true),store.values)
        }
    }
    @Test fun connectionOptionsNoLongerCarryHiddenAudioOverrides() {
        val fields=Options::class.java.declaredFields.map{it.name}
        keys.forEach{assertFalse(it, it in fields)}
        val options=Options(deviceName="Test phone",dpi=200,bindNetwork=false)
        assertEquals(options,options.copy())
    }
}
