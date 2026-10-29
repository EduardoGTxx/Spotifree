package com.spotifree.app.data.local

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.spotifree.app.data.db.AppDatabase
import com.spotifree.app.data.db.TrackEntity
import com.spotifree.app.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Songs stored on the phone (MediaStore), shown in Library > "En este dispositivo". */
class LocalMusicScanner(private val context: Context, private val db: AppDatabase) {

    fun observe(): Flow<List<Track>> = db.tracks().observeLocal().map { l -> l.map { it.toTrack() } }

    /** Re-reads the device library. Needs READ_MEDIA_AUDIO (Android 13+) or READ_EXTERNAL_STORAGE. */
    suspend fun scan(): Int = withContext(Dispatchers.IO) {
        val out = ArrayList<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000"
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val art = ContentUris.withAppendedId(android.net.Uri.parse("content://media/external/audio/albumart"), c.getLong(albumIdCol))
                val artist = c.getString(artistCol)?.takeUnless { it == "<unknown>" } ?: ""
                out += Track(
                    id = "local-$id",
                    title = c.getString(titleCol) ?: "?",
                    artist = artist,
                    album = c.getString(albumCol) ?: "",
                    durationSec = (c.getLong(durCol) / 1000).toInt(),
                    coverUrl = art.toString(),
                    localUri = uri.toString(),
                )
            }
        }
        db.tracks().upsertAll(out.map { TrackEntity.from(it) })
        db.tracks().deleteLocalExcept(out.map { it.id })
        out.size
    }
}
