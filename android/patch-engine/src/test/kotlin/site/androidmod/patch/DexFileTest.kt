package site.androidmod.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DexFileTest {
    private fun cls(d: String, vararg code: List<Int>) = TinyClass(d, code.map { TinyMethod(it) })

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
