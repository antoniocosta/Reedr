package net.uncorp.reedr.save

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** Downloads an article's images into its `img/` folder, four at a time; returns URL -> relative path (null = failed). */
class ImageDownloader(private val client: OkHttpClient, private val userAgent: () -> String) {

    suspend fun download(urls: List<String>, dir: File, referer: String): Map<String, String?> = coroutineScope {
        dir.mkdirs()
        val gate = Semaphore(4)
        urls.mapIndexed { index, url ->
            async(Dispatchers.IO) { url to gate.withPermit { fetchOne(url, dir, index.toString(), referer) } }
        }.awaitAll().toMap()
    }

    /** Saves one image as `<name>.<ext>`; returns `img/<name>.<ext>` or null. */
    suspend fun fetchOne(url: String, dir: File, name: String, referer: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", userAgent()).header("Referer", referer)
                .header("Accept", "image/avif,image/webp,image/*;q=0.8").build()
            client.newCall(request).execute().use { response ->
                val type = response.body.contentType()
                if (!response.isSuccessful || type?.type != "image") return@use null
                val length = response.body.contentLength()
                if (length > MAX_BYTES) return@use null
                val ext = when (type.subtype) {
                    "jpeg", "jpg", "pjpeg" -> "jpg"
                    "svg+xml" -> "svg"
                    else -> type.subtype.filter(Char::isLetterOrDigit).take(5).ifEmpty { "img" }
                }
                dir.mkdirs()
                val file = File(dir, "$name.$ext")
                val bytes = response.body.source().use { src ->
                    src.request(MAX_BYTES + 1)
                    if (src.buffer.size > MAX_BYTES) return@use null
                    src.buffer.readByteArray()
                } ?: return@use null
                if (bytes.size < MIN_BYTES) return@use null      // tracking pixels, spacers and grey placeholders
                file.writeBytes(bytes)
                "img/${file.name}"
            }
        }.getOrNull()
    }

    private companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
        const val MIN_BYTES = 1024
    }
}
