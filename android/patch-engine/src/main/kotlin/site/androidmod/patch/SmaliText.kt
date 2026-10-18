package site.androidmod.patch

/** Text forms the desktop's smali output uses, so the on-device scan sees what the desktop finder sees. */
object SmaliText {
    /** baksmali's `StringUtils.writeEscapedString`: quotes and backslash escaped, controls as \n \r \t or \uXXXX, non-ASCII as is. */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when {
                c == '\'' || c == '"' || c == '\\' -> sb.append('\\').append(c)
                c in ' '..'~' -> sb.append(c)
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x80 -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
