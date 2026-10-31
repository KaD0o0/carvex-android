package io.github.iamr0s.carvex.carplay

import io.github.iamr0s.carvex.carplay.core.IapFields
import io.github.iamr0s.carvex.carplay.core.IapWire

/** 24A446 accessoryd 0x10016d3ac: validate every known value in wire order
 * before applying Android endpoint requirements. All fields are optional here.
 * Repeated groups continue the same scalar slots and address arrays; a later
 * valid value must never hide an earlier malformed value. Unknown fields are
 * ignored by this handler, not recursively interpreted as known groups.
 * This normal form is local storage, not a new wire encoding or CF equivalence.
 */
internal object StartSessionSyntax {
    fun normalize(fields: IapFields): IapFields {
        val scalars = linkedMapOf<Int, ByteArray>()
        val groups = linkedMapOf<Int, MutableList<Pair<Int, ByteArray>>>()
        fun check(path: String, raw: ByteArray, width: Int? = null, boolean: Boolean = false) {
            if (width == null) {
                if (raw.isEmpty() || raw.last() != 0.toByte())
                    throw StartSessionConfigurationException(path, StartSessionProblem.INVALID_TEXT, raw.size)
            } else {
                if (raw.size != width)
                    throw StartSessionConfigurationException(path, StartSessionProblem.INVALID_WIDTH, raw.size)
                if (boolean && (raw[0].toInt() and 255) !in 0..1)
                    throw StartSessionConfigurationException(path, StartSessionProblem.INVALID_BOOLEAN, raw.size)
            }
        }
        for ((id, raw) in fields.ordered) {
            when (id) {
                0, 1, 7 -> {
                    val nested = try { IapWire.parseFieldList(raw) }
                    catch (_: IllegalArgumentException) {
                        throw StartSessionConfigurationException(id.toString(), StartSessionProblem.INVALID_GROUP, raw.size)
                    }
                    val values = groups.getOrPut(id) { mutableListOf() }
                    for ((leaf, value) in nested.ordered) {
                        val known = when (id) { 0 -> leaf == 0; 1 -> leaf in 0..4; else -> leaf in 0..1 }
                        if (!known) continue
                        val width = when { id == 1 && leaf in listOf(2, 4) -> 1; id == 7 && leaf == 1 -> 4; else -> null }
                        check("$id.$leaf", value, width)
                        val address = (id == 0 && leaf == 0) || (id == 1 && leaf == 3)
                        if (!address) values.removeAll { it.first == leaf }
                        values += leaf to value.copyOf()
                    }
                }
                2, 3, 4, 5, 6, 8 -> {
                    check(id.toString(), raw, when (id) { 2 -> 4; 8 -> 1; else -> null }, id == 8)
                    scalars[id] = raw.copyOf()
                }
            }
        }
        return IapFields(groups.map { (id, values) -> id to IapWire.fields(values) } + scalars.toList())
    }
}
