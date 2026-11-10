package moe.ouom.neriplayer.platform.search.api.codec

import java.util.Base64

private val QQMusicBase64Pattern = Regex("^[A-Za-z0-9+/=]+$")
private val QQMusicLrcTimestampPattern = Regex(
    "\\[(\\d{1,3}):(\\d{2})(?:[.:]\\d{1,3})?]"
)

/**
 * QQ 用一行 // 占位表示这句没有翻译
 *
 * 保留时间戳让翻译 matcher 可以消费这个空槽, 最终显示层会忽略占位文本
 */
fun stripUntranslatedPlaceholderLines(lyric: String?): String? {
    val source = lyric?.takeIf { it.isNotBlank() } ?: return null
    return source.lineSequence()
        .joinToString("\n") { line ->
            val closingBracket = line.indexOf(']')
            if (closingBracket < 0) {
                line
            } else {
                val text = line.substring(closingBracket + 1).trim()
                if (isQQMusicUntranslatedPlaceholder(text)) {
                    line.substring(0, closingBracket + 1) + "//"
                } else {
                    line
                }
            }
        }
        .takeIf { it.isNotBlank() }
}

private fun isQQMusicUntranslatedPlaceholder(text: String): Boolean {
    val normalized = text
        .replace('／', '/')
        .filterNot(Char::isWhitespace)
    return normalized.length >= 2 && normalized.all { it == '/' }
}

fun decodeQQMusicLyricPayload(rawValue: String?): String? {
    val raw = rawValue?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4 * 1024 * 1024 } ?: return null
    val decrypted = if (raw.length % 16 == 0 && raw.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
        decryptQqQrcHex(raw).takeIf(String::isNotBlank) ?: return null else raw
    val plain = htmlUnescapeQQMusic(decrypted)
    convertQQMusicQrcToYrc(plain)?.let { return it }
    if (QQMusicLrcTimestampPattern.containsMatchIn(plain)) return plain
    val decoded = decodeQQMusicBase64Lyric(plain) ?: return null
    val unescaped = htmlUnescapeQQMusic(decoded)
    return convertQQMusicQrcToYrc(unescaped)
        ?: unescaped.takeIf { QQMusicLrcTimestampPattern.containsMatchIn(it) }
}

/** Convert QQ word timings into the existing word-lyric format consumed throughout NeriPlayer. */
internal fun convertQQMusicQrcToYrc(raw: String): String? {
    val marker = "LyricContent=\""
    val start = raw.indexOf(marker)
    val body = if (start >= 0) {
        val from = start + marker.length
        val close = raw.lastIndexOf("/>").takeIf { it > from } ?: raw.length
        val to = raw.lastIndexOf('"', close.coerceAtMost(raw.lastIndex)).takeIf { it >= from } ?: return null
        raw.substring(from, to)
    } else raw
    val content = body.replace("&#10;", "\n").replace("&#13;", "\r")
        .replace("&lt;", "<").replace("&gt;", ">")
    val linePattern = Regex("^\\[(\\d+),(\\d+)](.*)$")
    val wordPattern = Regex("\\((\\d+),(\\d+)\\)")
    return content.lineSequence().mapNotNull { line ->
        val match = linePattern.matchEntire(line.trim()) ?: return@mapNotNull null
        val text = match.groupValues[3]
        if (!wordPattern.containsMatchIn(text)) return@mapNotNull null
        var cursor = 0
        val converted = buildString {
            append('[').append(match.groupValues[1]).append(',').append(match.groupValues[2]).append(']')
            for (word in wordPattern.findAll(text)) {
                val segment = text.substring(cursor, word.range.first)
                cursor = word.range.last + 1
                if (segment.isEmpty()) continue
                append('(').append(word.groupValues[1]).append(',').append(word.groupValues[2])
                    .append(",0)").append(segment)
            }
            append(text.substring(cursor))
        }
        converted.takeIf { wordPattern.replace(text, "").isNotBlank() }
    }.joinToString("\n").takeIf(String::isNotBlank)
}

fun decodeQQMusicBase64Lyric(value: String): String? {
    val compact = value.filterNot(Char::isWhitespace)
    if (compact.isEmpty() || compact.length % 4 != 0 || !QQMusicBase64Pattern.matches(compact)) {
        return null
    }
    return runCatching {
        String(Base64.getDecoder().decode(compact), Charsets.UTF_8)
    }.getOrNull()?.takeIf { QQMusicLrcTimestampPattern.containsMatchIn(it) || Regex("\\[\\d+,\\d+]").containsMatchIn(it) }
}

private fun htmlUnescapeQQMusic(value: String): String {
    return value
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
}
