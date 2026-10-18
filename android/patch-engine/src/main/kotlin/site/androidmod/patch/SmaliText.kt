package site.androidmod.patch

/** Text forms the desktop's smali output uses, so the on-device scan sees what the desktop finder sees. */
object SmaliText {
    /** baksmali's `StringUtils.writeEscapedString`: quotes and backslash escaped, \n \r \t as such, everything else (controls, 0x7f and up, surrogates one by one) as \uXXXX. */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when {
                c == '\'' || c == '"' || c == '\\' -> sb.append('\\').append(c)
                c in ' '..'~' -> sb.append(c)
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                else -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            }
        }
        return sb.toString()
    }
}
