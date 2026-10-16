package net.uncorp.reedr.data

import android.content.Context
import java.io.File

/**
 * On-disk layout: `files/articles/<id>/content.html` (the cleaned article body) and `files/articles/<id>/img/<n>.<ext>`.
 * The reader serves this folder at [READER_ORIGIN]/articles/ through WebViewAssetLoader.
 */
class ArticleFiles(context: Context) {
    val root = File(context.filesDir, "articles")

    fun dir(id: Long) = File(root, id.toString())
    fun content(id: Long) = File(dir(id), "content.html")
    fun imageDir(id: Long) = File(dir(id), "img")

    fun readContent(id: Long): String? = content(id).takeIf { it.exists() }?.readText()

    fun delete(id: Long) {
        dir(id).deleteRecursively()
    }

    companion object {
        const val READER_HOST = "appassets.androidplatform.net"
        const val READER_ORIGIN = "https://$READER_HOST"
    }
}
