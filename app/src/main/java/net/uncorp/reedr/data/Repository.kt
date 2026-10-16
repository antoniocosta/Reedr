package net.uncorp.reedr.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import net.uncorp.reedr.save.SaveWorker
import net.uncorp.reedr.save.Urls
import java.util.concurrent.TimeUnit

sealed interface SaveOutcome {
    data class Queued(val id: Long) : SaveOutcome
    data class AlreadySaved(val id: Long) : SaveOutcome
    data object NotALink : SaveOutcome
}

enum class ArticleFilter(val title: String) { ReadingList("Reading list"), Archive("Archive"), History("History") }

class Repository(context: Context, val db: AppDatabase, private val files: ArticleFiles) {
    private val work = WorkManager.getInstance(context)
    private val articles = db.articles()

    fun articles(filter: ArticleFilter): Flow<List<Article>> = when (filter) {
        ArticleFilter.ReadingList -> articles.readingList()
        ArticleFilter.Archive -> articles.archive()
        ArticleFilter.History -> articles.history()
    }

    /** The articles of a list matching [text] in any field: title, body, site, author, excerpt or URL. */
    fun search(filter: ArticleFilter, text: String): Flow<List<Article>> {
        val fts = ftsQuery(text) ?: return flowOf(emptyList())
        val like = "%" + text.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return when (filter) {
            ArticleFilter.ReadingList -> articles.searchList(archived = false, fts, like)
            ArticleFilter.Archive -> articles.searchList(archived = true, fts, like)
            ArticleFilter.History -> articles.searchHistory(fts, like)
        }
    }

    fun article(id: Long) = articles.observe(id)
    fun highlights(articleId: Long) = db.highlights().forArticle(articleId)

    // the row and its download job go together: a cancelled caller must not leave an article stuck in "Saving…"
    suspend fun saveFromText(text: String?): SaveOutcome = withContext(NonCancellable) { save(text) }

    private suspend fun save(text: String?): SaveOutcome {
        val url = Urls.findUrl(text)?.let(Urls::normalize) ?: return SaveOutcome.NotALink
        val existing = articles.byUrl(url)
        if (existing != null) {
            if (existing.status == SaveStatus.READY) {
                articles.update(existing.copy(archived = false, savedAt = System.currentTimeMillis()))
                return SaveOutcome.AlreadySaved(existing.id)
            }
            articles.update(existing.copy(status = SaveStatus.SAVING, error = null, savedAt = System.currentTimeMillis()))
            enqueue(existing.id)
            return SaveOutcome.Queued(existing.id)
        }
        val id = articles.insert(Article(url = url, title = Urls.host(url)))
        enqueue(id)
        return SaveOutcome.Queued(id)
    }

    /** Queues again every article still "Saving…" whose job is gone (app killed in between); a running job is left alone. */
    suspend fun resumePending() {
        articles.savingIds().forEach { enqueue(it, ExistingWorkPolicy.KEEP) }
    }

    suspend fun retry(id: Long) {
        val a = articles.get(id) ?: return
        articles.update(a.copy(status = SaveStatus.SAVING, error = null))
        enqueue(id)
    }

    suspend fun delete(id: Long) {
        work.cancelUniqueWork(workName(id))
        articles.deleteWithIndex(id)
        withContext(Dispatchers.IO) { files.delete(id) }
    }

    /** Deletes every article of a list (with its files and highlights). */
    suspend fun deleteAll(filter: ArticleFilter) {
        articles(filter).first().forEach { delete(it.id) }
    }

    suspend fun setArchived(id: Long, archived: Boolean) = articles.setArchived(id, archived)
    suspend fun setProgress(id: Long, progress: Float) = articles.setProgress(id, progress.coerceIn(0f, 1f))
    suspend fun markOpened(id: Long) = articles.setOpened(id, System.currentTimeMillis())
    suspend fun clearHistory() = articles.clearHistory()

    suspend fun addHighlight(articleId: Long, text: String, prefix: String, suffix: String): Long =
        db.highlights().insert(Highlight(articleId = articleId, text = text, prefix = prefix, suffix = suffix))

    suspend fun deleteHighlight(id: Long) = db.highlights().delete(id)

    suspend fun content(id: Long): String? = withContext(Dispatchers.IO) { files.readContent(id) }

    private fun enqueue(id: Long, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val request = OneTimeWorkRequestBuilder<SaveWorker>()
            .setInputData(workDataOf(SaveWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(workName(id), policy, request)
    }

    private fun workName(id: Long) = "save-$id"

    companion object {
        /** Turns user input into an FTS4 prefix query: every word must match, `"quoted"` syntax is neutralised. */
        fun ftsQuery(text: String): String? {
            val words = text.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            return words.joinToString(" ") { "$it*" }
        }
    }
}
