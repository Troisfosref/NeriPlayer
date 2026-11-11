package moe.ouom.neriplayer.platform.qqmusic.artist

// Full biography contract adapted from HGODLK/MusicOne, GPL-3.0-or-later.
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

internal fun parseQQArtistBiography(xml: String): String {
    require(xml.length<=2_000_000 && !xml.contains("<!DOCTYPE",ignoreCase=true) &&
        !xml.contains("<!ENTITY",ignoreCase=true))
    val document=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))
    require(document.getElementsByTagName("code").item(0)?.textContent=="0")
    val info=document.getElementsByTagName("info").item(0) as? Element ?: return ""
    fun Element.children(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    fun Element.fields(): String = children().filter { it.tagName=="item" }.joinToString("\n\n") { item ->
        val key=item.getElementsByTagName("key").item(0)?.textContent.orEmpty().trim()
        val value=item.getElementsByTagName("value").item(0)?.textContent.orEmpty().trim()
        if(key.isBlank()) value else "$key：$value"
    }
    return info.children().mapNotNull { section ->
        when(section.tagName) {
            "id" -> null
            "basic" -> section.fields().takeIf(String::isNotBlank)
            "group" -> section.children().joinToString("\n\n") { it.fields() }.takeIf(String::isNotBlank)
            else -> section.textContent.trim().takeIf(String::isNotBlank)
        }
    }.joinToString("\n\n")
}
