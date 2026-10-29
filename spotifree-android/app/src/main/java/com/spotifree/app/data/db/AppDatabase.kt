package com.spotifree.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackEntity::class,
        LikeEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        FollowedArtistEntity::class,
        BlockedArtistEntity::class,
        BlockedTrackEntity::class,
        DownloadEntity::class,
        HistoryEntity::class,
        SavedQueueEntity::class,
        StreamFormatEntity::class,
        LyricsCacheEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun likes(): LikeDao
    abstract fun playlists(): PlaylistDao
    abstract fun artists(): ArtistDao
    abstract fun blocks(): BlockDao
    abstract fun downloads(): DownloadDao
    abstract fun history(): HistoryDao
    abstract fun queue(): QueueDao
    abstract fun cache(): CacheDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "spotifree.db")
                .fallbackToDestructiveMigration(dropAllTables = false)
                .build()
    }
}
