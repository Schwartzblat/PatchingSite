package site.androidmod.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DexFileTest {
    private fun cls(d: String, vararg code: List<Int>) = TinyClass(d, code.map { TinyMethod(it) })

    private fun method(name: String, static: Boolean, params: List<String>, returns: String, code: List<Int>? = emptyList()) =
        TinyMethod(code, direct = static, flags = if (static) 0x8 else 0, name = name, params = params, returns = returns)

    @Test fun readsClassesAndConstStrings() {
        val strings = listOf("https://ads.example.com", "é", "😀", "a\u0000b", "unused")
        val dex = DexFile(TinyDex.build(strings, listOf(
            TinyClass("Lcom/applovin/a/B;", listOf(
                TinyMethod(TinyDex.constString(0) + TinyDex.constString(1)),
                TinyMethod(TinyDex.constString(2) + TinyDex.constString(3), direct = false),
            )),
            cls("Lx/Y;", TinyDex.constString(1)),
        )))
        assertEquals(listOf("Lcom/applovin/a/B;", "Lx/Y;"), dex.classes)
        assertEquals(listOf("https://ads.example.com", "é", "😀", "a\u0000b"), dex.constStrings(0))
        assertEquals(listOf("é"), dex.constStrings(1))
    }

    @Test fun directMethodsComeBeforeVirtual() {
        val dex = DexFile(TinyDex.build(listOf("v", "d"), listOf(TinyClass("LA;", listOf(
            TinyMethod(TinyDex.constString(0), direct = false), TinyMethod(TinyDex.constString(1))
        )))))
        assertEquals(listOf("d", "v"), dex.constStrings(0))
    }

    @Test fun jumboIndexIsRead() {
        val strings = List(70_000) { "s$it" }
        val dex = DexFile(TinyDex.build(strings, listOf(cls("LA;", TinyDex.constStringJumbo(69_999)))))
        assertEquals(listOf("s69999"), dex.constStrings(0))
    }

    @Test fun payloadsDoNotDerailTheWalk() {
        val code = TinyDex.constString(0) + TinyDex.packedSwitch(2) + TinyDex.constString(1) +
            TinyDex.sparseSwitch(3) + TinyDex.arrayData(1, 3) + TinyDex.constString(2) +
            listOf(0x0018, 0, 0, 0, 0) + listOf(0x00fa, 0, 0, 0) + TinyDex.constString(3)
        val dex = DexFile(TinyDex.build(listOf("A", "B", "C", "D"), listOf(cls("LA;", code))))
        assertEquals(listOf("A", "B", "C", "D"), dex.constStrings(0))
    }

    @Test fun classWithoutCodeIsListed() {
        val dex = DexFile(TinyDex.build(emptyList(), listOf(
            TinyClass("LNoData;", null),
            TinyClass("LAbstract;", listOf(TinyMethod(null))),
        )))
        assertEquals(listOf("LNoData;", "LAbstract;"), dex.classes)
        assertTrue(dex.constStrings(0).isEmpty())
        assertTrue(dex.constStrings(1).isEmpty())
    }

    @Test fun methodSigsReadStatics_PARAMS_AND_NAMES() {
        val dex = DexFile(TinyDex.build(listOf("S"), listOf(TinyClass("LA;", listOf(
            method("verify", static = true, params = listOf("Ljava/lang/String;", "Ljava/lang/String;"), returns = "Z"),
            method("verify3", static = true, params = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;"), returns = "Z"),
            method("log", static = false, params = listOf("Ljava/lang/String;"), returns = "V"),
            method("scratch", static = false, params = emptyList(), returns = "V", code = null),
        )))))
        assertEquals(listOf(
            DexMethodSig("verify", true, listOf("Ljava/lang/String;", "Ljava/lang/String;"), "Z"),
            DexMethodSig("verify3", true, listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;"), "Z"),
            DexMethodSig("log", false, listOf("Ljava/lang/String;"), "V"),
            DexMethodSig("scratch", false, emptyList(), "V"),
        ), dex.methodSigs(0))
    }

    @Test fun methodSigsOfAnEmptyClassIsEmpty() {
        val dex = DexFile(TinyDex.build(listOf("S"), listOf(TinyClass("LEmpty;"))))
        assertTrue(dex.methodSigs(0).isEmpty())
    }

    @Test fun methodSigsOfAClassWithoutClassData() {
        val dex = DexFile(TinyDex.build(emptyList(), listOf(TinyClass("LNoData;", null))))
        assertTrue(dex.methodSigs(0).isEmpty())
    }

    @Test fun truncatedMethodTableIsBadApk() {
        val bytes = TinyDex.build(listOf("S"), listOf(TinyClass("LA;", listOf(
            method("m", static = true, params = listOf("Ljava/lang/String;"), returns = "Z"),
        ))))
        val short = bytes.copyOf(bytes.size - 2) // the class_data ends with the code_off uleb, so this lands inside it
        try { DexFile(short).methodSigs(0); throw AssertionError("accepted") } catch (e: PatchException.BadApk) {
            assertTrue(e.message!!.startsWith("bad dex"))
        }
    }

    @Test fun protoWithoutParamsReadsEmptyParams() {
        val dex = DexFile(TinyDex.build(listOf("S"), listOf(TinyClass("LA;", listOf(
            method("gone", static = false, params = emptyList(), returns = "Z"),
        )))))
        assertEquals(listOf(DexMethodSig("gone", false, emptyList(), "Z")), dex.methodSigs(0))
    }

    @Test fun truncatedDexIsBadApk() {
        val bytes = TinyDex.build(listOf("A"), listOf(cls("LA;", TinyDex.constString(0))))
        for (cut in listOf(0x50, bytes.size - 4)) {
            val short = bytes.copyOf(cut)
            try {
                DexFile(short).let { d -> d.classes.indices.forEach { d.constStrings(it) } }
                throw AssertionError("cut at $cut was accepted")
            } catch (e: PatchException.BadApk) {
                assertTrue(e.message!!.startsWith("bad dex"))
            }
        }
    }

    @Test fun codeItemPastTheEndIsBadApk() {
        // The class_data comes after its code_item, so a plain cut can't reach the code_item walk: damage the code_item instead.
        val bytes = TinyDex.build(listOf("A"), listOf(cls("LA;", TinyDex.constString(0))))
        val n = bytes.size
        // the file ends with the method's code_off, a 2-byte uleb128
        val codeOff = (bytes[n - 2].toInt() and 0x7f) or ((bytes[n - 1].toInt() and 0x7f) shl 7)
        assertTrue(bytes[n - 2].toInt() < 0 && bytes[n - 1].toInt() >= 0)
        assertEquals("the code_item starts with registers_size 1", 1, bytes[codeOff].toInt())

        fun message(damaged: ByteArray): String = try {
            DexFile(damaged).constStrings(0)
            throw AssertionError("accepted")
        } catch (e: PatchException.BadApk) {
            e.message!!
        }

        val farCodeOff = bytes.copyOf().also { it[n - 2] = 0xff.toByte(); it[n - 1] = 0x7f }
        assertEquals("bad dex: code_item out of range", message(farCodeOff))

        val bigInsns = bytes.copyOf().also { it[codeOff + 14] = 0x7f } // insns_size = 0x7fxxxxxx units
        assertEquals("bad dex: insns out of range", message(bigInsns))
    }

    @Test fun instructionRunningPastTheEndIsBadApk() {
        val bytes = TinyDex.build(listOf("A"), listOf(cls("LA;", listOf(0x001a))))
        try { DexFile(bytes).constStrings(0); throw AssertionError("accepted") } catch (_: PatchException.BadApk) {}
    }

    @Test fun badStringIndexIsBadApk() {
        val bytes = TinyDex.build(listOf("A"), listOf(cls("LA;", TinyDex.constString(99))))
        try { DexFile(bytes).constStrings(0); throw AssertionError("accepted") } catch (_: PatchException.BadApk) {}
    }

    @Test fun unsupportedVersion() {
        val bytes = TinyDex.build(emptyList(), emptyList())
        "041".toByteArray().copyInto(bytes, 4)
        try { DexFile(bytes); throw AssertionError("accepted") } catch (_: PatchException.Unsupported) {}
    }
}
