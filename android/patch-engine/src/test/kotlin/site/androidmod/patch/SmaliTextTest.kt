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
            "é" to "\\u00e9",
            "\u007f" to "\\u007f",
            "😀" to "\\ud83d\\ude00",
        )
        for ((input, escaped) in cases) assertEquals(input, escaped, SmaliText.escape(input))
    }
}
