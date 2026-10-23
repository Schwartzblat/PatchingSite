package site.androidmod.patch

/** A declared method of a class: name, static-ness, and JVM-flavoured type descriptors (e.g. `"Ljava/lang/String;"`, `"Z"`). */
data class DexMethodSig(val name: String, val static: Boolean, val params: List<String>, val returns: String)

/**
 * A minimal dex reader for the ad scan: class descriptors and the `const-string` operands of each
 * class's methods. Reads [bytes] in place; the class list is parsed eagerly, strings decode on demand.
 */
class DexFile(private val bytes: ByteArray) {
    private val stringIdsOff: Int
    private val stringIdsSize: Int
    private val typeIdsOff: Int
    private val typeIdsSize: Int
    private val classDefsOff: Int
    private val classDefsSize: Int
    private val protoIdsOff: Int
    private val protoIdsSize: Int
    private val methodIdsOff: Int
    private val methodIdsSize: Int
    private val classDataOffs: IntArray

    /** Class descriptors in class_defs order, like `Lcom/applovin/a/B;`. */
    val classes: List<String>

    init {
        if (bytes.size < HEADER_SIZE) bad("header truncated")
        if (bytes[0] != 'd'.code.toByte() || bytes[1] != 'e'.code.toByte() || bytes[2] != 'x'.code.toByte() ||
            bytes[3] != '\n'.code.toByte() || bytes[7] != 0.toByte() || (4..6).any { bytes[it] !in '0'.code.toByte()..'9'.code.toByte() }
        ) bad("not a dex file")
        val version = String(bytes, 4, 3, Charsets.ISO_8859_1).toInt()
        if (version !in 35..39) throw PatchException.Unsupported("dex version $version")
        stringIdsSize = u32(0x38); stringIdsOff = u32(0x3c)
        typeIdsSize = u32(0x40); typeIdsOff = u32(0x44)
        classDefsSize = u32(0x60); classDefsOff = u32(0x64)
        protoIdsSize = u32(0x48); protoIdsOff = u32(0x4c)
        methodIdsSize = u32(0x58); methodIdsOff = u32(0x5c)
        table(stringIdsOff, stringIdsSize, 4, "string_ids")
        table(typeIdsOff, typeIdsSize, 4, "type_ids")
        table(protoIdsOff, protoIdsSize, 12, "proto_ids")
        table(methodIdsOff, methodIdsSize, 8, "method_ids")
        table(classDefsOff, classDefsSize, 32, "class_defs")
        classDataOffs = IntArray(classDefsSize)
        val names = ArrayList<String>(classDefsSize)
        for (i in 0 until classDefsSize) {
            val def = classDefsOff + i * 32
            val typeIdx = u32(def)
            if (typeIdx >= typeIdsSize) bad("class_idx $typeIdx out of range")
            names.add(stringAt(u32(typeIdsOff + typeIdx * 4)))
            classDataOffs[i] = u32(def + 24)
        }
        classes = names
    }

    /** Decoded operands of `const-string` and `const-string/jumbo` in the class's methods: direct, then virtual, in code order. */
    fun constStrings(classIndex: Int): List<String> {
        val out = ArrayList<String>()
        val dataOff = classDataOffs[classIndex]
        if (dataOff == 0) return out
        val r = Cursor(dataOff)
        val staticFields = r.uleb(); val instanceFields = r.uleb()
        val direct = r.uleb(); val virtual = r.uleb()
        for (i in 0 until (staticFields.toLong() + instanceFields)) { r.uleb(); r.uleb() }
        for (i in 0 until (direct.toLong() + virtual)) {
            r.uleb(); r.uleb()
            val codeOff = r.uleb()
            if (codeOff != 0) walk(codeOff, out)
        }
        return out
    }

    /**
     * The class's declared methods, in class_data order (direct then virtual, exactly as written).
     * Static-ness comes from the entry's access flags (`0x8` = `AccStatic`); the name and prototype
     * descriptors are resolved through method_ids and proto_ids. `code_off` is read even for abstract
     * methods (0) but carries no signature meaning. Any malformed table read is `BadApk("bad dex: …")`.
     */
    fun methodSigs(classIndex: Int): List<DexMethodSig> {
        val dataOff = classDataOffs[classIndex]
        if (dataOff == 0) return emptyList()
        val r = Cursor(dataOff)
        val staticFields = r.uleb(); val instanceFields = r.uleb()
        val direct = r.uleb(); val virtual = r.uleb()
        for (i in 0 until (staticFields.toLong() + instanceFields)) { r.uleb(); r.uleb() }
        val out = ArrayList<DexMethodSig>()
        for (count in listOf(direct, virtual)) { // each list restarts the idx_diff baseline at 0
            var methodIdx = 0
            for (i in 0 until count) {
                methodIdx += r.uleb() // idx_diff
                val flags = r.uleb()
                r.uleb() // code_off (0 for abstract/native, no signature meaning)
                if (methodIdx < 0 || methodIdx >= methodIdsSize) bad("method idx $methodIdx out of range")
                val id = methodIdsOff + methodIdx * 8 // class_idx u16, proto_idx u16, name_idx u32
                val protoIdx = u16(id + 2)
                if (protoIdx < 0 || protoIdx >= protoIdsSize) bad("proto idx $protoIdx out of range")
                val proto = protoIdsOff + protoIdx * 12 // shorty_idx u32, return_type_idx u32, parameters_off u32
                val paramsOff = u32(proto + 8)
                out.add(DexMethodSig(
                    stringAt(u32(id + 4)),
                    flags and 0x8 != 0,
                    if (paramsOff == 0) emptyList() else typeList(paramsOff),
                    typeAt(u32(proto + 4)),
                ))
            }
        }
        return out
    }

    /** The type descriptor at type_ids [idx], like `Ljava/lang/String;` or `Z`. */
    private fun typeAt(idx: Int): String {
        if (idx < 0 || idx >= typeIdsSize) bad("type idx $idx out of range")
        return stringAt(u32(typeIdsOff + idx * 4))
    }

    /** The type_list at [off]: u32 size, then size type indices. */
    private fun typeList(off: Int): List<String> {
        val size = u32(off)
        if (off + 4 + size.toLong() * 2 > bytes.size) bad("parameters type list out of range")
        return (0 until size).map { typeAt(u16(off + 4 + it * 2)) }
    }

    private fun walk(codeOff: Int, out: MutableList<String>) {
        if (codeOff < 0 || codeOff.toLong() + 16 > bytes.size) bad("code_item out of range")
        val size = u32(codeOff + 12)
        val start = codeOff + 16
        if (start.toLong() + size.toLong() * 2 > bytes.size) bad("insns out of range")
        fun unit(pc: Int): Int {
            if (pc >= size) bad("instruction runs past insns_size")
            return u16(start + pc * 2)
        }
        var pc = 0
        while (pc < size) {
            val u = unit(pc)
            val width: Long = when (u) {
                0x0100 -> unit(pc + 1) * 2L + 4
                0x0200 -> unit(pc + 1) * 4L + 2
                0x0300 -> {
                    val elementWidth = unit(pc + 1).toLong()
                    val count = unit(pc + 2).toLong() or (unit(pc + 3).toLong() shl 16)
                    (elementWidth * count + 1) / 2 + 4
                }
                else -> WIDTHS[u and 0xff].toLong()
            }
            if (pc + width > size) bad("instruction runs past insns_size")
            when (u and 0xff) {
                0x1a -> out.add(stringAt(unit(pc + 1)))
                0x1b -> out.add(stringAt(unit(pc + 1) or (unit(pc + 2) shl 16)))
            }
            pc += width.toInt()
        }
    }

    private fun stringAt(idx: Int): String {
        if (idx < 0 || idx >= stringIdsSize) bad("string index $idx out of range")
        return string(u32(stringIdsOff + idx * 4))
    }

    /** Reads the string_data_item at [off]: uleb128 utf16_size, then MUTF-8 up to a 0 byte. */
    private fun string(off: Int): String {
        val r = Cursor(off)
        val utf16Size = r.uleb()
        val sb = StringBuilder(minOf(utf16Size, 1 shl 16))
        while (true) {
            val b = r.byte()
            when {
                b == 0 -> return sb.toString()
                b < 0x80 -> sb.append(b.toChar())
                b and 0xe0 == 0xc0 -> sb.append((((b and 0x1f) shl 6) or r.cont()).toChar())
                b and 0xf0 == 0xe0 -> {
                    val c1 = r.cont(); val c2 = r.cont()
                    sb.append((((b and 0x0f) shl 12) or (c1 shl 6) or c2).toChar())
                }
                else -> bad("bad MUTF-8 lead byte")
            }
        }
    }

    private inner class Cursor(var pos: Int) {
        fun byte(): Int {
            if (pos < 0 || pos >= bytes.size) bad("read past end of file")
            return bytes[pos++].toInt() and 0xff
        }
        fun cont(): Int {
            val b = byte()
            if (b and 0xc0 != 0x80) bad("bad MUTF-8 continuation byte")
            return b and 0x3f
        }
        fun uleb(): Int {
            var result = 0L
            var shift = 0
            while (true) {
                val b = byte()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 28) bad("uleb128 too long")
            }
            if (result > Int.MAX_VALUE) bad("uleb128 too large")
            return result.toInt()
        }
    }

    private fun table(off: Int, count: Int, entry: Int, name: String) {
        if (count > 0 && off.toLong() + count.toLong() * entry > bytes.size) bad("$name out of range")
    }

    private fun u16(off: Int): Int {
        if (off < 0 || off + 2 > bytes.size) bad("read past end of file")
        return (bytes[off].toInt() and 0xff) or ((bytes[off + 1].toInt() and 0xff) shl 8)
    }

    /** An unsigned 32-bit field as an Int; values above Int.MAX_VALUE cannot be valid offsets or counts. */
    private fun u32(off: Int): Int {
        if (off < 0 || off + 4 > bytes.size) bad("read past end of file")
        val v = (bytes[off].toInt() and 0xff) or ((bytes[off + 1].toInt() and 0xff) shl 8) or
            ((bytes[off + 2].toInt() and 0xff) shl 16) or ((bytes[off + 3].toInt() and 0xff) shl 24)
        if (v < 0) bad("value too large at $off")
        return v
    }

    private fun bad(msg: String): Nothing = throw PatchException.BadApk("bad dex: $msg")

    private companion object {
        const val HEADER_SIZE = 0x70

        /** Instruction width in 16-bit units, by opcode. */
        val WIDTHS = IntArray(256) { 1 }.also { w ->
            fun set(width: Int, vararg ops: Int) = ops.forEach { w[it] = width }
            fun range(width: Int, from: Int, to: Int) = (from..to).forEach { w[it] = width }
            set(2, 0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20, 0x22, 0x23, 0x29, 0xfe, 0xff)
            range(2, 0x2d, 0x3d); range(2, 0x44, 0x6d); range(2, 0x90, 0xaf); range(2, 0xd0, 0xe2)
            set(3, 0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25, 0x26, 0x2a, 0x2b, 0x2c, 0xfc, 0xfd)
            range(3, 0x6e, 0x72); range(3, 0x74, 0x78)
            set(4, 0xfa, 0xfb)
            set(5, 0x18)
        }
    }
}
