package site.androidmod.store.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The committed libfastsigner.so files run from an app's nativeLibraryDir, under
 * Android's app seccomp filter. A glibc build (static or not) dies there with SIGSYS
 * (glibc's startup calls set_robust_list), so each must be a bionic executable: right
 * machine, and a PT_INTERP naming /system/bin/linker64. Parsed straight from the ELF bytes.
 */
class FastsignerBinariesTest {
    private val jniLibs = File("src/main/jniLibs")

    private class Elf(val machine: Int, val interpreter: String?)

    private fun parse(file: File): Elf {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("not an ELF file: $file", 0x464C457F, b.getInt(0))
        assertEquals("not ELF64: $file", 2, b.get(4).toInt())
        val machine = b.getShort(18).toInt() and 0xFFFF
        val phoff = b.getLong(32).toInt()
        val phentsize = b.getShort(54).toInt() and 0xFFFF
        val phnum = b.getShort(56).toInt() and 0xFFFF
        var interp: String? = null
        for (i in 0 until phnum) {
            val ph = phoff + i * phentsize
            if (b.getInt(ph) == PT_INTERP) {
                val off = b.getLong(ph + 8).toInt()
                val size = b.getLong(ph + 32).toInt()
                val raw = ByteArray(size).also { b.get(off, it) }
                interp = String(raw).trimEnd('\u0000')
            }
        }
        return Elf(machine, interp)
    }

    private fun check(abi: String, machine: Int) {
        val file = File(jniLibs, "$abi/libfastsigner.so")
        assertTrue("missing $file", file.isFile)
        val elf = parse(file)
        assertEquals("$abi e_machine", machine, elf.machine)
        assertNotNull("$abi has no PT_INTERP (a static glibc build?)", elf.interpreter)
        assertEquals("$abi interpreter", "/system/bin/linker64", elf.interpreter)
    }

    @Test fun arm64IsABionicAarch64Executable() = check("arm64-v8a", EM_AARCH64)

    @Test fun x86_64IsABionicX8664Executable() = check("x86_64", EM_X86_64)

    private companion object {
        const val PT_INTERP = 3
        const val EM_X86_64 = 62
        const val EM_AARCH64 = 183
    }
}
