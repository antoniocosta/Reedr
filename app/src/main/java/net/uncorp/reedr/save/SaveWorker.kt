package net.uncorp.reedr.save

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.uncorp.reedr.R
import net.uncorp.reedr.ReedrApp
import net.uncorp.reedr.data.ArticleFts
import net.uncorp.reedr.data.SaveStatus
import java.io.IOException
import java.io.InterruptedIOException

/** Downloads, extracts and stores one article (input: its id). Network errors retry twice, then the article is marked failed. */
class SaveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as ReedrApp).graph
        val id = inputData.getLong(KEY_ID, -1)
        val dao = graph.db.articles()
        val article = dao.get(id) ?: return Result.success()
        return try {
            val page = withContext(Dispatchers.IO) { graph.fetcher.fetch(article.url) }
            var extracted = graph.extractor.fromHtml(page.html, page.finalUrl)
            if (extracted == null || extracted.length < MIN_LENGTH) {
                graph.extractor.fromRenderedPage(page.finalUrl)?.let { rendered ->
                    if (extracted == null || rendered.length > extracted!!.length) extracted = rendered
                }
            }
            val result = extracted ?: throw UnsupportedPageException("Couldn't find an article on this page")

            val files = graph.files
            files.delete(id)
            val title = result.title?.takeIf { it.isNotBlank() } ?: Urls.host(page.finalUrl)
            val downloadImages = graph.settings.reader.value.downloadImages
            val (cleaned, leadImage) = withContext(Dispatchers.Default) {
                val prepared = ArticleCleaner.prepare(result.content, page.finalUrl, title)
                val local = if (downloadImages) graph.images.download(prepared.imageUrls, files.imageDir(id), page.finalUrl) else emptyMap()
                val cleaned = ArticleCleaner.finish(prepared, local)
                val firstLocal = prepared.imageUrls.firstNotNullOfOrNull { local[it] }
                val lead = firstLocal ?: (result.leadImageUrl ?: page.leadImageUrl)?.takeIf { downloadImages }
                    ?.let { graph.images.fetchOne(it, files.imageDir(id), "lead", page.finalUrl) }
                cleaned to lead
            }
            withContext(Dispatchers.IO) {
                files.dir(id).mkdirs()
                files.content(id).writeText(cleaned.html)
            }
            dao.putFts(ArticleFts(id, title, cleaned.text))
            dao.update(
                (dao.get(id) ?: return Result.success()).copy(
                    title = title,
                    siteName = result.siteName ?: Urls.host(page.finalUrl),
                    byline = result.byline?.take(200),
                    excerpt = result.excerpt?.take(400) ?: cleaned.text.take(300),
                    lang = result.lang,
                    wordCount = cleaned.wordCount,
                    leadImage = leadImage,
                    status = SaveStatus.READY,
                    error = null,
                ),
            )
            Result.success()
        } catch (e: UnsupportedPageException) {
            fail(id, e.message)
        } catch (e: IOException) {
            if (runAttemptCount < 2) Result.retry()
            else fail(id, if (e is InterruptedIOException) "The site took too long to answer" else e.message ?: "Network error")
        }
    }

    private suspend fun fail(id: Long, message: String?): Result {
        val dao = (applicationContext as ReedrApp).graph.db.articles()
        dao.get(id)?.let { dao.update(it.copy(status = SaveStatus.FAILED, error = message ?: "Couldn't save this page")) }
        return Result.failure()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.saving_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.saving_article))
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val KEY_ID = "id"
        private const val MIN_LENGTH = 500
        private const val CHANNEL = "saving"
        private const val NOTIFICATION_ID = 1
    }
}
