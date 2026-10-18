package site.androidmod.patch

import org.junit.Assert.assertEquals
import org.junit.Test

class AbiChoiceTest {
    private val p = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private val e = listOf("x86_64", "x86", "arm64-v8a", "armeabi-v7a")

    private fun target(abi: String, dir: String = abi) = Pair(LibTarget(abi, dir), null)

    @Test fun sixtyFourBitApp() =
        assertEquals(target("arm64-v8a"), AbiChoice.choose(setOf("arm64-v8a", "armeabi-v7a"), p))

    @Test fun thirtyTwoBitOnly() = assertEquals(target("armeabi-v7a"), AbiChoice.choose(setOf("armeabi-v7a"), p))

    @Test fun oldArmeabi() = assertEquals(target("armeabi-v7a", "armeabi"), AbiChoice.choose(setOf("armeabi"), p))

    @Test fun noNativeCode() {
        assertEquals(target("arm64-v8a"), AbiChoice.choose(emptySet(), p))
        assertEquals(target("x86_64"), AbiChoice.choose(emptySet(), e))
    }

    @Test fun armAppOnX86() =
        assertEquals(Pair(null, CantPatch.NEEDS_TRANSLATION), AbiChoice.choose(setOf("arm64-v8a"), e))

    @Test fun riscv() =
        assertEquals(Pair(null, CantPatch.UNSUPPORTED_ABI), AbiChoice.choose(emptySet(), listOf("riscv64")))

    @Test(expected = PatchException.BadApk::class) fun libsFittingNoDeviceAbiAreBadApk() {
        AbiChoice.choose(setOf("mips"), p)
    }

    @Test(expected = IllegalArgumentException::class) fun noDeviceAbisIsRejected() {
        AbiChoice.choose(emptySet(), emptyList())
    }
}
