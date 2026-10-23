package dev.pokescan.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CollectionDao {
    @Query("SELECT * FROM collections ORDER BY createdAt, id")
    abstract fun observeCollections(): Flow<List<CardCollection>>

    @Insert
    abstract suspend fun insertCollection(collection: CardCollection): Long

    @Update
    abstract suspend fun updateCollection(collection: CardCollection)

    @Query("DELETE FROM collections WHERE id = :id")
    abstract suspend fun deleteCollectionRow(id: Long)

    @Query("DELETE FROM collection WHERE collectionId = :collectionId")
    abstract suspend fun deleteEntriesIn(collectionId: Long)

    /** Deletes the collection and every card in it. */
    @Transaction
    open suspend fun deleteCollection(id: Long) {
        deleteEntriesIn(id)
        deleteCollectionRow(id)
    }

    @Query("SELECT * FROM collection WHERE collectionId = :collectionId ORDER BY addedAt DESC")
    abstract fun observeEntries(collectionId: Long): Flow<List<CollectionEntry>>

    @Query("SELECT * FROM collection ORDER BY addedAt DESC")
    abstract fun observeAll(): Flow<List<CollectionEntry>>

    @Query("SELECT * FROM collection ORDER BY setName, name")
    abstract suspend fun getAll(): List<CollectionEntry>

    @Query("SELECT * FROM collection WHERE collectionId = :collectionId ORDER BY setName, name")
    abstract suspend fun getEntries(collectionId: Long): List<CollectionEntry>

    @Query(
        "SELECT * FROM collection WHERE collectionId = :collectionId AND cardId = :cardId " +
            "AND variant = :variant AND condition = :condition LIMIT 1"
    )
    abstract suspend fun find(collectionId: Long, cardId: String, variant: String, condition: String): CollectionEntry?

    @Insert
    abstract suspend fun insert(entry: CollectionEntry): Long

    @Update
    abstract suspend fun update(entry: CollectionEntry)

    @Delete
    abstract suspend fun delete(entry: CollectionEntry)

    /** Adds the entry's copies, stacking onto a matching card/variant/condition if one exists. */
    @Transaction
    open suspend fun addCopies(entry: CollectionEntry) {
        val existing = find(entry.collectionId, entry.cardId, entry.variant, entry.condition)
        if (existing != null) update(existing.copy(quantity = existing.quantity + entry.quantity))
        else insert(entry)
    }

    @Transaction
    open suspend fun addAll(entries: List<CollectionEntry>) {
        entries.forEach { addCopies(it) }
    }

    /** Moves [quantity] copies of [entry] into another collection. */
    @Transaction
    open suspend fun move(entry: CollectionEntry, targetCollectionId: Long, quantity: Int) {
        val count = quantity.coerceIn(1, entry.quantity)
        addCopies(entry.copy(id = 0, collectionId = targetCollectionId, quantity = count))
        if (count == entry.quantity) delete(entry) else update(entry.copy(quantity = entry.quantity - count))
    }
}

@Database(entities = [CollectionEntry::class, CardCollection::class], version = 4)
abstract class AppDatabase : RoomDatabase() {
    abstract fun collectionDao(): CollectionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v2 adds collections; existing cards go into the default collection. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collections` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                insertDefaultCollection(db)
                db.execSQL("ALTER TABLE `collection` ADD COLUMN `collectionId` INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** v3 adds card types; they're filled in by the next price refresh. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `collection` ADD COLUMN `types` TEXT")
            }
        }

        /** v4 adds Pokédex numbers; they're filled in by the next price refresh. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `collection` ADD COLUMN `dexNumber` INTEGER")
            }
        }

        private fun insertDefaultCollection(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT OR IGNORE INTO `collections` (`id`, `name`, `createdAt`) VALUES (?, ?, ?)",
                arrayOf<Any>(DEFAULT_COLLECTION_ID, DEFAULT_COLLECTION_NAME, System.currentTimeMillis()),
            )
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "pokescan.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) = insertDefaultCollection(db)
                })
                .build()
                .also { instance = it }
        }
    }
}
