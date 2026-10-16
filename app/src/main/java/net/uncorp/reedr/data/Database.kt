package net.uncorp.reedr.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles WHERE archived = 0 ORDER BY savedAt DESC")
    fun readingList(): Flow<List<Article>>

    @Query("SELECT * FROM articles WHERE archived = 1 ORDER BY savedAt DESC")
    fun archive(): Flow<List<Article>>

    @Query("SELECT * FROM articles WHERE lastOpenedAt IS NOT NULL ORDER BY lastOpenedAt DESC")
    fun history(): Flow<List<Article>>

    // search inside one list: title and body through the full-text index, the other fields with LIKE
    @Query("SELECT * FROM articles WHERE archived = :archived AND (id IN (SELECT rowid FROM article_fts WHERE article_fts MATCH :fts) OR siteName LIKE :like ESCAPE '\\' OR byline LIKE :like ESCAPE '\\' OR excerpt LIKE :like ESCAPE '\\' OR url LIKE :like ESCAPE '\\') ORDER BY savedAt DESC")
    fun searchList(archived: Boolean, fts: String, like: String): Flow<List<Article>>

    @Query("SELECT * FROM articles WHERE lastOpenedAt IS NOT NULL AND (id IN (SELECT rowid FROM article_fts WHERE article_fts MATCH :fts) OR siteName LIKE :like ESCAPE '\\' OR byline LIKE :like ESCAPE '\\' OR excerpt LIKE :like ESCAPE '\\' OR url LIKE :like ESCAPE '\\') ORDER BY lastOpenedAt DESC")
    fun searchHistory(fts: String, like: String): Flow<List<Article>>

    @Query("SELECT * FROM articles WHERE id = :id")
    fun observe(id: Long): Flow<Article?>

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun get(id: Long): Article?

    @Query("SELECT id FROM articles WHERE status = 'SAVING'")
    suspend fun savingIds(): List<Long>

    @Query("SELECT * FROM articles WHERE url = :url")
    suspend fun byUrl(url: String): Article?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(article: Article): Long

    @Update
    suspend fun update(article: Article)

    @Query("UPDATE articles SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("UPDATE articles SET progress = :progress WHERE id = :id")
    suspend fun setProgress(id: Long, progress: Float)

    @Query("UPDATE articles SET lastOpenedAt = :at WHERE id = :id")
    suspend fun setOpened(id: Long, at: Long)

    @Query("UPDATE articles SET lastOpenedAt = NULL")
    suspend fun clearHistory()

    @Query("DELETE FROM articles WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFts(fts: ArticleFts)

    @Query("DELETE FROM article_fts WHERE rowid = :id")
    suspend fun deleteFts(id: Long)

    @Transaction
    suspend fun deleteWithIndex(id: Long) {
        deleteFts(id)
        delete(id)
    }
}

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights WHERE articleId = :articleId ORDER BY id")
    fun forArticle(articleId: Long): Flow<List<Highlight>>

    @Insert
    suspend fun insert(highlight: Highlight): Long

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun delete(id: Long)
}

class Converters {
    @TypeConverter fun statusToString(s: SaveStatus): String = s.name
    @TypeConverter fun stringToStatus(s: String): SaveStatus = SaveStatus.valueOf(s)
}

@Database(entities = [Article::class, Highlight::class, ArticleFts::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun articles(): ArticleDao
    abstract fun highlights(): HighlightDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "reedr.db").build()
    }
}
