package site.androidmod.patch

import java.io.ByteArrayOutputStream

/** A method of a [TinyDex] class: raw 16-bit code units, or null for an abstract/native method (code_off 0). */
class TinyMethod(val code: List<Int>?, val direct: Boolean = true)

/** A class: [methods] null means no class_data at all (class_data_off 0). */
class TinyClass(val descriptor: String, val methods: List<TinyMethod>? = emptyList())

/**
 * Writes a minimal dex for tests. String indices: [strings] keep their positions 0..n-1; every class
 * descriptor not already present is appended after them (in class order). Fields the reader ignores are zero.
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
        classes.forEach { if (it.descriptor !in table) table.add(it.descriptor) }
        val typeStr = classes.map { table.indexOf(it.descriptor) }
        val strOff = 0x70
        val typeOff = strOff + table.size * 4
        val classOff = typeOff + classes.size * 4
        val dataStart = classOff + classes.size * 32

        val data = ByteArrayOutputStream()
        fun pos() = dataStart + data.size()
        val strPos = table.map { s -> pos().also { data.write(uleb(s.length)); data.write(mutf8(s)); data.write(0) } }
        val classDataPos = classes.map { c ->
            val ms = c.methods ?: return@map 0
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
                for (i in direct + virtual) { data.write(uleb(0)); data.write(uleb(1)); data.write(uleb(codePos[i])) }
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
        put(0x40, classes.size); put(0x44, typeOff)
        put(0x60, classes.size); put(0x64, classOff)
        out.write(header)
        strPos.forEach { out.write(le32(it)) }
        typeStr.forEach { out.write(le32(it)) }
        classes.forEachIndexed { i, _ ->
            out.write(le32(i)); out.write(le32(1)); out.write(le32(-1)); out.write(le32(0))
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
