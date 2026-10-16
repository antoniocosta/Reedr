package net.uncorp.reedr

import android.app.Application
import android.content.Context
import net.uncorp.reedr.data.AppDatabase
import net.uncorp.reedr.data.ArticleFiles
import net.uncorp.reedr.data.Repository
import net.uncorp.reedr.data.Settings
import net.uncorp.reedr.save.ImageDownloader
import net.uncorp.reedr.save.PageFetcher
import net.uncorp.reedr.save.ReadabilityExtractor
import net.uncorp.reedr.save.UserAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ReedrApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        graph.scope.launch { graph.repository.resumePending() }
    }
}

/** The app's few long-lived objects, created once. */
class Graph(context: Context) {
    val db = AppDatabase.create(context)
    val files = ArticleFiles(context)
    val settings = Settings(context)
    val repository = Repository(context, db, files)
    /** For work that outlives a screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
    private val userAgent: String by lazy { UserAgent.chrome(context) }

    val fetcher = PageFetcher(http) { userAgent }
    val images = ImageDownloader(http) { userAgent }
    val extractor = ReadabilityExtractor(context)
}

val Context.graph: Graph get() = (applicationContext as ReedrApp).graph
