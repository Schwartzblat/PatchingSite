package site.androidmod.patch

import org.junit.Assert.assertEquals
import org.junit.Test

class SmaliTextTest {
    @Test fun escapesLikeBaksmali() {
        val cases = listOf(
            "https://a.b/\"x\"" to "https://a.b/\\\"x\\\"",
            "a'b\\c" to "a\\'b\\\\c",
            "t\tn\nr\r" to "t\\tn\\nr\\r",
            "\u0001" to "\\u0001",
            "é" to "é",
            "\u007f" to "\\u007f",
            "😀" to "😀",
        )
        for ((input, escaped) in cases) assertEquals(input, escaped, SmaliText.escape(input))
    }
}
