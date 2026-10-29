package com.spotifree.app.data.importer

import com.spotifree.app.data.model.Track

/**
 * Playlist CSV files from Exportify, Spotlistr, TuneMyMusic, Soundiiz or any "title, artist"
 * spreadsheet (port of the previous app's importer). Songs are found on YouTube when played.
 */
object CsvImporter {
    class CsvException(message: String) : Exception(message)

    fun parse(text: String, fileName: String?): Pair<String, List<Track>> {
        val rows = rows(text)
        if (rows.isEmpty()) throw CsvException("empty")
        val title = fileName?.substringBeforeLast('.')?.replace(Regex("[_-]"), " ")?.trim()?.ifBlank { null } ?: "Playlist de Spotify"
        val tracks = rows.mapIndexedNotNull { idx, row ->
            val rawTitle = row.first("track name", "title", "track", "song name", "song", "nombre", "cancion") ?: return@mapIndexedNotNull null
            val artist = row.first("artist name(s)", "artist name", "artist", "artists", "artista", "artistas") ?: "Varios Artistas"
            val album = row.first("album name", "album", "disco") ?: ""
            val dur = row.first("duration (ms)", "track duration (ms)", "duration_ms", "duration")?.toLongOrNull()
            val durationSec = when {
                dur == null -> 0
                dur > 1000 -> (dur / 1000).toInt()
                else -> dur.toInt()
            }
            val uri = row.first("track uri", "spotify id", "spotify uri", "uri")
            val cleanTitle = rawTitle.removePrefix("e:")
            val id = uri?.removePrefix("spotify:track:")?.takeIf { it.isNotBlank() }?.let { "spotify-$it" }
                ?: SpotifyImporter.makeTrack(cleanTitle, artist, album, null, null).id
            Track(
                id = id,
                title = cleanTitle,
                artist = artist,
                album = album,
                durationSec = durationSec,
                searchQuery = "$artist $cleanTitle",
            )
        }.distinctBy { it.id }
        if (tracks.isEmpty()) throw CsvException("no_tracks")
        return title to tracks
    }

    private fun Map<String, String>.first(vararg keys: String): String? = keys.firstNotNullOfOrNull { this[it]?.takeIf { v -> v.isNotBlank() } }

    fun rows(text: String): List<Map<String, String>> {
        val clean = text.removePrefix("﻿").trim()
        if (clean.isEmpty()) return emptyList()
        val lines = clean.split(Regex("\r?\n"))
        if (lines.size < 2) return emptyList()
        val first = lines[0]
        val delimiter = if (first.count { it == ';' } > first.count { it == ',' }) ';' else ','
        val headers = split(first, delimiter).map { it.trim().lowercase() }
        return lines.drop(1).filter { it.isNotBlank() }.map { line ->
            val values = split(line.trim(), delimiter)
            headers.mapIndexed { i, h -> h to (values.getOrNull(i) ?: "").trim() }.toMap()
        }
    }

    private fun split(line: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && line.getOrNull(i + 1) == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }
}
