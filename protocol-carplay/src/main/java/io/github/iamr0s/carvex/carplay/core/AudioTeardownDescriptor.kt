package io.github.iamr0s.carvex.carplay.core

/**
 * Audio stream descriptor used by the firmware's single-stream teardown path.
 *
 * 24A446 calls apsession_TeardownStream with NULL options for raw100/101/102.
 * That path creates a descriptor containing only the raw stream type.  The
 * streamConnectionID remains a SETUP/KDF identity and must not be copied into
 * this descriptor by the host.
 */
object AudioTeardownDescriptor {
    fun forType(type:Int):Map<String,Any?> = linkedMapOf("type" to type)
}
