package net.uncorp.reedr.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SaveStatus { SAVING, READY, FAILED }

@Entity(tableName = "articles", indices = [Index(value = ["url"], unique = true), Index("savedAt"), Index("lastOpenedAt")])
data class Article(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val siteName: String? = null,
    val byline: String? = null,
    val excerpt: String? = null,
    val lang: String? = null,
    val status: SaveStatus = SaveStatus.SAVING,
    val error: String? = null,
    val savedAt: Long = System.currentTimeMillis(),
    /** Done with: out of the reading list, in the Archive. */
    val archived: Boolean = false,
    /** Reading position, 0..1 of the scrollable height. */
    val progress: Float = 0f,
    /** When it was last opened, for History (null = not in History). */
    val lastOpenedAt: Long? = null,
    val wordCount: Int = 0,
    /** Path of the lead image relative to the article's directory, e.g. `img/0.jpg`. */
    val leadImage: String? = null,
) {
    val readingMinutes: Int get() = if (wordCount <= 0) 0 else maxOf(1, wordCount / 230)
}

/** A highlight is anchored by its text plus a little context on each side, so it survives re-rendering. */
@Entity(
    tableName = "highlights",
    indices = [Index("articleId")],
    foreignKeys = [ForeignKey(entity = Article::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.CASCADE)],
)
data class Highlight(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val articleId: Long,
    val text: String,
    val prefix: String,
    val suffix: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Full-text index over title and body; rowid is the article id. */
@Fts4
@Entity(tableName = "article_fts")
data class ArticleFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val title: String,
    val body: String,
)
