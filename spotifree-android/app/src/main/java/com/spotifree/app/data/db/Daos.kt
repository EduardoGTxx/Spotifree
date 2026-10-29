package com.spotifree.app.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Upsert
    suspend fun upsert(track: TrackEntity)

    @Upsert
    suspend fun upsertAll(tracks: List<TrackEntity>)

    /** Adds songs that are not in the library yet; existing rows (and their resolved data) stay. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun get(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE videoId = :videoId LIMIT 1")
    suspend fun getByVideoId(videoId: String): TrackEntity?

    @Query("UPDATE tracks SET videoId = :videoId, coverUrl = COALESCE(:coverUrl, coverUrl), durationSec = CASE WHEN :durationSec > 0 THEN :durationSec ELSE durationSec END, enrichFailedAt = NULL WHERE id = :id")
    suspend fun setResolved(id: String, videoId: String, coverUrl: String?, durationSec: Int)

    @Query("UPDATE tracks SET enrichFailedAt = :at WHERE id = :id")
    suspend fun markEnrichFailed(id: String, at: Long)

    @Query("UPDATE tracks SET coverUrl = :coverUrl WHERE id = :id")
    suspend fun setCover(id: String, coverUrl: String?)

    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :q || '%' OR artist LIKE '%' || :q || '%' LIMIT :limit")
    suspend fun searchLocal(q: String, limit: Int = 50): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE localUri IS NOT NULL ORDER BY title COLLATE NOCASE")
    fun observeLocal(): Flow<List<TrackEntity>>

    @Query("SELECT id FROM tracks WHERE localUri IS NOT NULL")
    suspend fun localIds(): List<String>

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<String>)

    /** SQLite on Android 10 allows 999 bound variables: diff in Kotlin, delete in chunks. */
    suspend fun deleteLocalExcept(keep: List<String>) {
        val keepSet = keep.toHashSet()
        localIds().filter { it !in keepSet }.chunked(500).forEach { deleteIds(it) }
    }

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query("SELECT * FROM tracks")
    suspend fun all(): List<TrackEntity>
}

@Dao
interface LikeDao {
    @Query("SELECT t.* FROM likes l JOIN tracks t ON t.id = l.trackId ORDER BY l.likedAt DESC")
    fun observeLikedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT t.* FROM likes l JOIN tracks t ON t.id = l.trackId ORDER BY l.likedAt DESC")
    suspend fun likedTracks(): List<TrackEntity>

    @Query("SELECT trackId FROM likes")
    fun observeLikedIds(): Flow<List<String>>

    @Query("SELECT trackId FROM likes ORDER BY likedAt DESC")
    suspend fun likedIds(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM likes WHERE trackId = :trackId)")
    suspend fun isLiked(trackId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(like: LikeEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(likes: List<LikeEntity>)

    @Query("DELETE FROM likes WHERE trackId = :trackId")
    suspend fun delete(trackId: String)

    @Query("DELETE FROM likes WHERE trackId IN (:ids)")
    suspend fun deleteMany(ids: List<String>)

    @Query("SELECT COUNT(*) FROM likes")
    fun observeCount(): Flow<Int>
}

data class PlaylistWithCount(
    @Embedded val playlist: PlaylistEntity,
    val count: Int,
)

@Dao
interface PlaylistDao {
    @Query("SELECT p.*, (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.id) AS count FROM playlists p ORDER BY p.sortIndex DESC")
    fun observeAll(): Flow<List<PlaylistWithCount>>

    @Query("SELECT p.*, (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.id) AS count FROM playlists p ORDER BY p.sortIndex DESC")
    suspend fun all(): List<PlaylistWithCount>

    @Query("SELECT p.*, (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.id) AS count FROM playlists p WHERE p.id = :id")
    fun observe(id: String): Flow<PlaylistWithCount?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE youtubePlaylistId = :ytId LIMIT 1")
    suspend fun getByYoutubeId(ytId: String): PlaylistEntity?

    @Upsert
    suspend fun upsert(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE playlists SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("UPDATE playlists SET coverUrl = :coverUrl WHERE id = :id")
    suspend fun setCover(id: String, coverUrl: String?)

    @Query("SELECT t.* FROM playlist_tracks pt JOIN tracks t ON t.id = pt.trackId WHERE pt.playlistId = :playlistId ORDER BY pt.position")
    fun observeTracks(playlistId: String): Flow<List<TrackEntity>>

    @Query("SELECT t.* FROM playlist_tracks pt JOIN tracks t ON t.id = pt.trackId WHERE pt.playlistId = :playlistId ORDER BY pt.position")
    suspend fun tracks(playlistId: String): List<TrackEntity>

    @Query("SELECT trackId FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position")
    suspend fun trackIds(playlistId: String): List<String>

    @Query("SELECT COALESCE(MAX(position), 0) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: String): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTracks(rows: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrack(playlistId: String, trackId: String)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearTracks(playlistId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId)")
    suspend fun contains(playlistId: String, trackId: String): Boolean

    @Transaction
    suspend fun replaceTracks(playlistId: String, trackIds: List<String>) {
        clearTracks(playlistId)
        insertTracks(trackIds.distinct().mapIndexed { i, id -> PlaylistTrackEntity(playlistId, id, (i + 1).toLong()) })
    }
}

@Dao
interface ArtistDao {
    @Query("SELECT * FROM followed_artists ORDER BY followedAt DESC")
    fun observeFollowed(): Flow<List<FollowedArtistEntity>>

    @Query("SELECT * FROM followed_artists ORDER BY followedAt DESC")
    suspend fun followed(): List<FollowedArtistEntity>

    @Upsert
    suspend fun follow(artist: FollowedArtistEntity)

    @Query("DELETE FROM followed_artists WHERE `key` = :key")
    suspend fun unfollow(key: String)

    @Query("SELECT EXISTS(SELECT 1 FROM followed_artists WHERE `key` = :key)")
    fun observeIsFollowed(key: String): Flow<Boolean>
}

@Dao
interface BlockDao {
    @Query("SELECT * FROM blocked_artists ORDER BY name")
    fun observeArtists(): Flow<List<BlockedArtistEntity>>

    @Query("SELECT * FROM blocked_tracks")
    fun observeTracks(): Flow<List<BlockedTrackEntity>>

    @Query("SELECT * FROM blocked_artists")
    suspend fun artists(): List<BlockedArtistEntity>

    @Query("SELECT trackId FROM blocked_tracks")
    suspend fun trackIds(): List<String>

    @Upsert
    suspend fun blockArtist(a: BlockedArtistEntity)

    @Upsert
    suspend fun blockTrack(t: BlockedTrackEntity)

    @Query("DELETE FROM blocked_artists WHERE `key` = :key")
    suspend fun unblockArtist(key: String)

    @Query("DELETE FROM blocked_tracks WHERE trackId = :trackId")
    suspend fun unblockTrack(trackId: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT trackId FROM downloads WHERE state = 2")
    fun observeDoneIds(): Flow<List<String>>

    @Query("SELECT t.* FROM downloads d JOIN tracks t ON t.id = d.trackId WHERE d.state = 2 ORDER BY d.savedAt DESC")
    fun observeDoneTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM downloads WHERE trackId = :trackId")
    suspend fun get(trackId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE state = 2")
    suspend fun done(): List<DownloadEntity>

    @Upsert
    suspend fun upsert(d: DownloadEntity)

    @Query("UPDATE downloads SET state = :state, progress = :progress, error = :error WHERE trackId = :trackId")
    suspend fun setState(trackId: String, state: Int, progress: Float, error: String? = null)

    @Query("UPDATE downloads SET lastAccessedAt = :at WHERE trackId = :trackId")
    suspend fun touch(trackId: String, at: Long = System.currentTimeMillis())

    @Query("DELETE FROM downloads WHERE trackId = :trackId")
    suspend fun delete(trackId: String)

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM downloads WHERE state = 2")
    fun observeTotalBytes(): Flow<Long>

    @Query("SELECT * FROM downloads WHERE state = 2 AND isManual = 0 ORDER BY lastAccessedAt ASC")
    suspend fun autoCachedOldestFirst(): List<DownloadEntity>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM downloads WHERE state = 2 AND isManual = 0")
    suspend fun autoCachedBytes(): Long
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(h: HistoryEntity)

    @Insert
    suspend fun insertAll(h: List<HistoryEntity>)

    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int

    @Query("DELETE FROM history WHERE id IN (SELECT id FROM history ORDER BY playedAt ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)

    @Query("SELECT trackId, title, artist, coverUrl, COUNT(*) AS plays, SUM(secondsPlayed) AS seconds FROM history WHERE playedAt >= :from AND playedAt < :to GROUP BY trackId ORDER BY plays DESC, seconds DESC LIMIT :limit")
    suspend fun topTracks(from: Long, to: Long, limit: Int): List<TrackPlayCount>

    @Query("SELECT artist, COUNT(*) AS plays, SUM(secondsPlayed) AS seconds FROM history WHERE playedAt >= :from AND playedAt < :to GROUP BY artist ORDER BY plays DESC LIMIT :limit")
    suspend fun topArtists(from: Long, to: Long, limit: Int): List<ArtistPlayCount>

    @Query("SELECT COALESCE(SUM(secondsPlayed), 0) FROM history WHERE playedAt >= :from AND playedAt < :to")
    suspend fun totalSeconds(from: Long, to: Long): Long

    @Query("SELECT COUNT(*) FROM history WHERE playedAt >= :from AND playedAt < :to")
    suspend fun totalPlays(from: Long, to: Long): Int

    @Query("SELECT strftime('%Y-%m-%d', playedAt / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS plays, SUM(secondsPlayed) AS seconds FROM history WHERE playedAt >= :from AND playedAt < :to GROUP BY day ORDER BY day")
    suspend fun byDay(from: Long, to: Long): List<DayCount>

    @Query("SELECT CAST(strftime('%H', playedAt / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour, COUNT(*) AS plays FROM history WHERE playedAt >= :from AND playedAt < :to GROUP BY hour ORDER BY plays DESC")
    suspend fun byHour(from: Long, to: Long): List<HourCount>

    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<HistoryEntity>

    @Query("SELECT h.* FROM history h INNER JOIN (SELECT trackId, MAX(playedAt) AS lastPlayed FROM history WHERE source = 'app' GROUP BY trackId) x ON h.trackId = x.trackId AND h.playedAt = x.lastPlayed ORDER BY h.playedAt DESC LIMIT :limit")
    fun observeRecentDistinct(limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT MIN(playedAt) FROM history")
    suspend fun firstPlayedAt(): Long?

    @Query("DELETE FROM history WHERE source = :source")
    suspend fun deleteSource(source: String)

    @Query("SELECT COUNT(*) FROM history WHERE source = :source")
    suspend fun countSource(source: String): Int
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM saved_queue ORDER BY position")
    suspend fun all(): List<SavedQueueEntity>

    @Query("DELETE FROM saved_queue")
    suspend fun clear()

    @Insert
    suspend fun insertAll(rows: List<SavedQueueEntity>)

    @Transaction
    suspend fun replace(trackIds: List<String>) {
        clear()
        insertAll(trackIds.mapIndexed { i, id -> SavedQueueEntity(i, id) })
    }
}

@Dao
interface CacheDao {
    @Upsert
    suspend fun putFormat(f: StreamFormatEntity)

    @Query("SELECT * FROM stream_formats WHERE videoId = :videoId")
    suspend fun format(videoId: String): StreamFormatEntity?

    @Upsert
    suspend fun putLyrics(l: LyricsCacheEntity)

    @Query("SELECT * FROM lyrics_cache WHERE `key` = :key")
    suspend fun lyrics(key: String): LyricsCacheEntity?

    @Query("DELETE FROM lyrics_cache WHERE `key` NOT IN (SELECT `key` FROM lyrics_cache ORDER BY savedAt DESC LIMIT :keep)")
    suspend fun trimLyrics(keep: Int)

    @Query("DELETE FROM lyrics_cache")
    suspend fun clearLyrics()
}
