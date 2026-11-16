package moe.ouom.neriplayer.platform.qqmusic.api

import org.json.JSONObject

/** Output only public identifiers, numeric result codes and counts; never stringify the API body. */
internal fun qqPlaybackDiagnosticMid(mid: String): String = when {
    mid.isBlank() -> "missing"
    mid.matches(Regex("[A-Za-z0-9]{1,64}")) -> mid
    else -> "invalid"
}

internal fun qqPlaybackResponseDiagnostic(response: JSONObject): String {
    val items = response.optJSONArray("midurlinfo")
    val results = mutableSetOf<Int>()
    var paths = 0
    var previews = 0
    for (index in 0 until (items?.length() ?: 0)) {
        val item = items?.optJSONObject(index) ?: continue
        val fields = listOf("result", "code", "errtype").filter(item::has)
        results += if (fields.isEmpty()) -1 else fields.map { item.optInt(it, -1) }.firstOrNull { it != 0 } ?: 0
        for (field in listOf("purl", "wifiurl")) {
            val path = item.optString(field)
            if (path.isNotBlank()) {
                paths++
                val previewQuery = path.substringAfter('?', "").substringBefore('#').split('&')
                    .any { it.substringBefore('=').equals("src", true) }
                if (previewQuery || path.substringBefore('?').substringAfterLast('/').startsWith("C400", true)) previews++
            }
        }
    }
    return "items=${items?.length() ?: 0} codes=${results.sorted().joinToString(",").ifEmpty { "none" }} " +
        "purls=$paths previewCandidates=$previews cdnRoots=${response.optJSONArray("sip")?.length() ?: 0}"
}
