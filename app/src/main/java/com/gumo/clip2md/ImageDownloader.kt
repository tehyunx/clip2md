package com.gumo.clip2md

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads every http(s) image referenced in a markdown document so the
 * saved note is self-contained instead of just holding remote links.
 */
object ImageDownloader {
    private val IMG_REGEX = Regex("""!\[([^\]]*)]\((https?://[^)\s]+)\)""")

    /**
     * Downloads each referenced image into [imagesDir] and rewrites its link.
     * When [absoluteLinks] is true the link becomes a `file://` URI (for local
     * in-app preview); otherwise it becomes a relative `images/name` path
     * (for a portable folder export).
     */
    fun process(markdown: String, imagesDir: File, absoluteLinks: Boolean): String {
        imagesDir.mkdirs()
        var index = 0
        return IMG_REGEX.replace(markdown) { match ->
            val alt = match.groupValues[1]
            val url = match.groupValues[2]
            index++
            try {
                val ext = guessExtension(url)
                val file = File(imagesDir, "img$index$ext")
                downloadTo(url, file)
                val link = if (absoluteLinks) "file://${file.absolutePath}" else "images/${file.name}"
                "![$alt]($link)"
            } catch (e: Exception) {
                match.value
            }
        }
    }

    private fun guessExtension(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val dot = clean.lastIndexOf('.')
        val ext = if (dot != -1 && clean.length - dot <= 5) clean.substring(dot) else ""
        return if (ext.matches(Regex("""\.[a-zA-Z0-9]{2,4}"""))) ext else ".jpg"
    }

    private fun downloadTo(urlStr: String, file: File) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.inputStream.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        conn.disconnect()
    }
}
