package io.github.iamr0s.carvex.carplay.core

/** Stable diagnostic keys keep a dynamic warning from disappearing in the controller's dispatch. */
object VideoDiagnosticLog {
    const val WRITE_STALLED_MS="write-stalled-ms"

    fun message(name:String,value:Long):String?=when(name){
        WRITE_STALLED_MS->"Video write stalled ageMs=$value; audio/control remain independent; receiver playback not confirmed"
        else->null
    }
}
