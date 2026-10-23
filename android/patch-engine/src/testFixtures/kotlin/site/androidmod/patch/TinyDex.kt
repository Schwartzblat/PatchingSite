package site.androidmod.patch

import java.io.ByteArrayOutputStream

/**
 * A method of a [TinyDex] class: raw 16-bit code units, or null for an abstract/native method (code_off 0).
 * [flags] go verbatim into the class_data entry (`0x8` for `AccStatic`); [name], [params] and [returns]
 * (both type descriptors) fill the method_ids and proto_ids tables — the method_ids index of a method is
 * its position across all classes in declaration order, and the default name is `tm` + that index.
 * These fixtures are synthetic: method_ids are not sorted by (class_idx, proto_idx, name_idx), and the
 * default `flags = 0` with `direct = true` can express static/direct shapes a real dex would not contain;
 * the reader under test depends on neither.
 */
class TinyMethod(
    val code: List<Int>?,
    val direct: Boolean = true,
    val flags: Int = 0,
    val name: String? = null,
    val params: List<String> = emptyList(),
    val returns: String = "V",
)

/** A class: [methods] null means no class_data at all (class_data_off 0). */
class TinyClass(val descriptor: String, val methods: List<TinyMethod>? = emptyList())

/**
 * Writes a minimal dex for tests. String indices: [strings] keep their positions 0..n-1; every class
 * descriptor, method name and param/return type descriptor not already present is appended after them
 * (class descriptors first, in class order). Protos are deduped by (returns, params); protos without
 * parameters get `parameters_off` 0; every declared method gets a real method_ids entry in declaration
 * order, and class_data's idx_diff sequences match (one baseline reset per direct/virtual list).
 */
object TinyDex {
    fun constString(idx: Int) = listOf(0x001a, idx)
    fun constStringJumbo(idx: Int) = listOf(0x001b, idx and 0xffff, idx ushr 16)
    fun packedSwitch(targets: Int) = listOf(0x0100, targets, 0, 0) + List(targets * 2) { 0 }
    fun sparseSwitch(targets: Int) = listOf(0x0200, targets) + List(targets * 4) { 0 }
    /** Array-data payload; [size] elements of [elementWidth] bytes each (filled with 0x11 bytes). */
    fun arrayData(elementWidth: Int, size: Int): List<Int> {
        val units = (elementWidth * size + 1) / 2
        return listOf(0x0300, elementWidth, size and 0xffff, size ushr 16) + List(units) { 0x1111 }
    }

    fun build(strings: List<String>, classes: List<TinyClass>): ByteArray {
        val table = strings.toMutableList()
        val typeStrings = ArrayList<String>()
        fun stringIdx(s: String): Int = table.indexOf(s).let { if (it >= 0) it else { table.add(s); table.lastIndex } }
        fun typeIdx(s: String): Int = typeStrings.indexOf(s).let { if (it >= 0) it else { typeStrings.add(s); typeStrings.lastIndex } }
        fun addType(d: String): Int { stringIdx(d); return typeIdx(d) }
        fun shorty(returns: String, params: List<String>): String {
            fun c(d: String) = if (d[0] == '[') 'L' else d[0]
            return (listOf(returns) + params).joinToString("") { c(it).toString() }
        }

        classes.forEach { addType(it.descriptor) }
        val classTypes = classes.map { typeIdx(it.descriptor) }

        val protos = LinkedHashMap<Pair<String, List<String>>, Int>() // (return desc, param descs) -> proto idx
        fun protoOf(returns: String, params: List<String>): Int =
            protos.getOrPut(returns to params) { stringIdx(shorty(returns, params)); protos.size }
        val methodIds = ArrayList<Triple<Int, Int, Int>>()            // (class_type, proto_idx, name_str) in declaration order
        val methodIdx = classes.mapIndexed { ci, c ->
            (c.methods ?: emptyList()).map { m ->
                val mid = methodIds.size
                val nameIdx = stringIdx(m.name ?: "tm$mid")
                addType(m.returns)
                m.params.forEach { addType(it) }
                val protoIdx = protoOf(m.returns, m.params)
                methodIds.add(Triple(classTypes[ci], protoIdx, nameIdx))
                mid
            }
        }

        val strOff = 0x70
        val typeOff = strOff + table.size * 4
        val protoOff = typeOff + typeStrings.size * 4
        val methodOff = protoOff + protos.size * 12
        val classOff = methodOff + methodIds.size * 8
        val dataStart = classOff + classes.size * 32

        val data = ByteArrayOutputStream()
        fun pos() = dataStart + data.size()
        val strPos = table.map { s -> pos().also { data.write(uleb(s.length)); data.write(mutf8(s)); data.write(0) } }
        // One type_list per distinct (non-empty) params list, shared by the protos that declare it.
        val paramsPos = LinkedHashMap<List<String>, Int>()
        protos.keys.forEach { (_, params) ->
            if (params.isNotEmpty() && params !in paramsPos) {
                paramsPos[params] = pos().also {
                    data.write(le32(params.size))
                    params.forEach { p -> data.write(le16(typeIdx(p))) }
                }
            }
        }
        val classDataPos = classes.mapIndexed { ci, c ->
            val ms = c.methods ?: return@mapIndexed 0
            val codePos = ms.map { m -> m.code?.let { code ->
                pos().also {
                    data.write(le16(1)); data.write(le16(0)); data.write(le16(0)); data.write(le16(0))
                    data.write(le32(0)); data.write(le32(code.size))
                    code.forEach { u -> data.write(le16(u)) }
                }
            } ?: 0 }
            pos().also {
                val direct = ms.indices.filter { ms[it].direct }
                val virtual = ms.indices.filter { !ms[it].direct }
                data.write(uleb(0)); data.write(uleb(0)); data.write(uleb(direct.size)); data.write(uleb(virtual.size))
                for (list in listOf(direct, virtual)) {
                    var prev = 0
                    for (i in list) {
                        val idx = methodIdx[ci][i]
                        data.write(uleb(idx - prev)); prev = idx
                        data.write(uleb(ms[i].flags)); data.write(uleb(codePos[i]))
                    }
                }
            }
        }

        val out = ByteArrayOutputStream()
        val header = ByteArray(0x70)
        "dex\n035\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(header)
        fun put(off: Int, v: Int) = le32(v).copyInto(header, off)
        put(0x20, dataStart + data.size())
        put(0x24, 0x70)
        put(0x28, 0x12345678)
        put(0x38, table.size); put(0x3c, strOff)
        put(0x40, typeStrings.size); put(0x44, typeOff)
        put(0x48, protos.size); put(0x4c, protoOff)
        put(0x58, methodIds.size); put(0x5c, methodOff)
        put(0x60, classes.size); put(0x64, classOff)
        out.write(header)
        strPos.forEach { out.write(le32(it)) }
        typeStrings.forEach { out.write(le32(table.indexOf(it))) }
        protos.forEach { (sig, _) ->
            val (returns, params) = sig
            out.write(le32(stringIdx(shorty(returns, params))))
            out.write(le32(typeIdx(returns)))
            out.write(le32(paramsPos[params] ?: 0))
        }
        methodIds.forEach { (cls, protoIdx, nameIdx) ->
            out.write(le16(cls)); out.write(le16(protoIdx)); out.write(le32(nameIdx))
        }
        classes.forEachIndexed { i, _ ->
            out.write(le32(classTypes[i])); out.write(le32(1)); out.write(le32(-1)); out.write(le32(0))
            out.write(le32(-1)); out.write(le32(0)); out.write(le32(classDataPos[i])); out.write(le32(0))
        }
        out.write(data.toByteArray())
        return out.toByteArray()
    }

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun uleb(v: Int): ByteArray {
        val o = ByteArrayOutputStream(); var x = v
        while (true) {
            val b = x and 0x7f; x = x ushr 7
            if (x == 0) { o.write(b); break } else o.write(b or 0x80)
        }
        return o.toByteArray()
    }

    private fun mutf8(s: String): ByteArray {
        val o = ByteArrayOutputStream()
        for (c in s) {
            val v = c.code
            when {
                v == 0 -> { o.write(0xc0); o.write(0x80) }
                v < 0x80 -> o.write(v)
                v < 0x800 -> { o.write(0xc0 or (v shr 6)); o.write(0x80 or (v and 0x3f)) }
                else -> { o.write(0xe0 or (v shr 12)); o.write(0x80 or ((v shr 6) and 0x3f)); o.write(0x80 or (v and 0x3f)) }
            }
        }
        return o.toByteArray()
    }
}
