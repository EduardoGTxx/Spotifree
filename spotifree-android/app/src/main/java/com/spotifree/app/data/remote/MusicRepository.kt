package com.spotifree.app.data.remote

import com.spotifree.app.util.runCatchingCancellable

import com.spotifree.app.data.model.AlbumRef
import com.spotifree.app.data.model.ArtistPage
import com.spotifree.app.data.model.ArtistRef
import com.spotifree.app.data.model.CollectionPage
import com.spotifree.app.data.model.PlaylistRef
import com.spotifree.app.data.model.SearchFilter
import com.spotifree.app.data.model.SearchResults
import com.spotifree.app.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the app reads from YouTube / YouTube Music, through NewPipe Extractor:
 * search, suggestions, artists, albums, playlists and radio (YouTube Mix).
 */
class MusicRepository {
    private val yt get() = ServiceList.YouTube

    private val searchCache = object : LinkedHashMap<String, SearchResults>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchResults>?) = size > 40
    }
    private val radioCache = ConcurrentHashMap<String, List<Track>>()

    private fun filterFor(f: SearchFilter) = when (f) {
        SearchFilter.SONGS -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
        SearchFilter.VIDEOS -> YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS
        SearchFilter.ALBUMS -> YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
        SearchFilter.ARTISTS -> YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS
        SearchFilter.PLAYLISTS -> YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS
    }

    private fun searchItems(query: String, contentFilter: String): List<InfoItem> {
        val handler = yt.searchQHFactory.fromQuery(query, listOf(contentFilter), "")
        return SearchInfo.getInfo(yt, handler).relatedItems
    }

    /** Songs for a free-text query (YouTube Music "Songs" shelf, videos as a fallback). */
    suspend fun searchSongs(query: String, limit: Int = 25): List<Track> = withContext(Dispatchers.IO) {
        val songs = runCatchingCancellable { searchItems(query, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS) }
            .getOrDefault(emptyList())
            .mapNotNull { (it as? StreamInfoItem)?.toTrack() }
        val result = if (songs.isNotEmpty()) songs else runCatchingCancellable {
            searchItems(query, YoutubeSearchQueryHandlerFactory.VIDEOS)
        }.getOrDefault(emptyList()).mapNotNull { (it as? StreamInfoItem)?.toTrack() }
        result.take(limit)
    }

    suspend fun search(query: String, filter: SearchFilter? = null): SearchResults = withContext(Dispatchers.IO) {
        val key = "$query|$filter"
        synchronized(searchCache) { searchCache[key] }?.let { return@withContext it }
        val filters = filter?.let { listOf(it) }
            ?: listOf(SearchFilter.SONGS, SearchFilter.ARTISTS, SearchFilter.ALBUMS, SearchFilter.PLAYLISTS)
        val results = coroutineScope {
            filters.map { f -> f to async { runCatchingCancellable { searchItems(query, filterFor(f)) }.getOrDefault(emptyList()) } }
                .associate { (f, d) -> f to d.await() }
        }
        val songs = (results[SearchFilter.SONGS].orEmpty() + results[SearchFilter.VIDEOS].orEmpty())
            .mapNotNull { (it as? StreamInfoItem)?.toTrack() }
        val out = SearchResults(
            songs = songs,
            artists = results[SearchFilter.ARTISTS].orEmpty().mapNotNull { (it as? ChannelInfoItem)?.toArtistRef() },
            albums = results[SearchFilter.ALBUMS].orEmpty().mapNotNull { (it as? PlaylistInfoItem)?.toAlbumRef() },
            playlists = results[SearchFilter.PLAYLISTS].orEmpty().mapNotNull { (it as? PlaylistInfoItem)?.toPlaylistRef() },
        )
        if (out.songs.isNotEmpty() || out.artists.isNotEmpty()) synchronized(searchCache) { searchCache[key] = out }
        out
    }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        runCatchingCancellable { yt.suggestionExtractor.suggestionList(query).take(8) }.getOrDefault(emptyList())
    }

    /** First match for an imported song (artist + title), used to resolve Spotify/CSV imports. */
    suspend fun findBestMatch(artist: String, title: String, durationSec: Int = 0, query: String? = null): Track? {
        val q = query?.takeIf { it.isNotBlank() } ?: "$artist $title".trim()
        if (q.isBlank()) return null
        val candidates = searchSongs(q, 8)
        if (candidates.isEmpty()) return null
        val nTitle = normalize(title)
        val nArtist = normalize(artist.split(Regex(""",|&| x | feat\.? """, RegexOption.IGNORE_CASE)).first())
        return candidates.maxByOrNull { c ->
            var score = 0
            val ct = normalize(c.title)
            val ca = normalize(c.artist)
            if (ct == nTitle) score += 6 else if (nTitle.isNotEmpty() && (ct.contains(nTitle) || nTitle.contains(ct))) score += 3
            if (ca == nArtist) score += 4 else if (nArtist.isNotEmpty() && (ca.contains(nArtist) || nArtist.contains(ca))) score += 2
            if (durationSec > 0 && c.durationSec > 0) {
                val diff = kotlin.math.abs(c.durationSec - durationSec)
                score += when {
                    diff <= 3 -> 4
                    diff <= 10 -> 2
                    diff > 40 -> -4
                    else -> 0
                }
            }
            score
        }
    }

    suspend fun artist(nameOrUrl: String): ArtistPage = withContext(Dispatchers.IO) {
        val url = if (nameOrUrl.startsWith("http")) nameOrUrl else {
            searchItems(nameOrUrl, YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS)
                .firstNotNullOfOrNull { (it as? ChannelInfoItem)?.url }
                ?: throw IllegalStateException("artist_not_found")
        }
        val info = ChannelInfo.getInfo(yt, url)
        val name = YouTubeIds.cleanArtist(info.name)
        coroutineScope {
            val songsD = async {
                runCatchingCancellable { searchItems(name, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS) }.getOrDefault(emptyList())
                    .mapNotNull { (it as? StreamInfoItem)?.toTrack() }
                    .let { list ->
                        val own = list.filter { normalize(it.artist).contains(normalize(name)) }
                        if (own.size >= 5) own else list
                    }
                    .take(20)
            }
            val albumsD = async {
                val fromTab = runCatchingCancellable {
                    info.tabs.firstOrNull { tab -> tab.contentFilters.any { it == ChannelTabs.ALBUMS } }?.let { tab ->
                        ChannelTabInfo.getInfo(yt, tab).relatedItems.mapNotNull { (it as? PlaylistInfoItem)?.toAlbumRef(name) }
                    }
                }.getOrNull().orEmpty()
                fromTab.ifEmpty {
                    runCatchingCancellable { searchItems(name, YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS) }.getOrDefault(emptyList())
                        .mapNotNull { (it as? PlaylistInfoItem)?.toAlbumRef() }
                        .filter { normalize(it.artist).contains(normalize(name)) || it.artist.isBlank() }
                        .take(20)
                }
            }
            ArtistPage(
                name = name,
                imageUrl = YouTubeIds.bestImage(info.avatars),
                bannerUrl = YouTubeIds.bestImage(info.banners),
                subscribers = info.subscriberCount,
                description = info.description,
                topTracks = songsD.await(),
                albums = albumsD.await(),
                url = url,
            )
        }
    }

    /** Album or playlist page with all its songs (follows continuation pages, bounded). */
    suspend fun collection(url: String, maxTracks: Int = 2000): CollectionPage = withContext(Dispatchers.IO) {
        val info = PlaylistInfo.getInfo(yt, url)
        val tracks = info.relatedItems.mapNotNull { it.toTrack() }.toMutableList()
        var page: Page? = info.nextPage
        var guard = 0
        while (page != null && tracks.size < maxTracks && guard++ < 40) {
            val more = runCatchingCancellable { PlaylistInfo.getMoreItems(yt, url, page) }.getOrNull() ?: break
            tracks += more.items.mapNotNull { it.toTrack() }
            page = if (more.hasNextPage()) more.nextPage else null
        }
        val cover = YouTubeIds.bestImage(info.thumbnails) ?: tracks.firstOrNull()?.artworkUrl
        // Album songs inherit the album cover (their own thumbnails are video frames)
        val isAlbum = YouTubeIds.playlistId(url)?.startsWith("OLAK5uy_") == true
        // YouTube names auto-generated albums "Album – <name>" / "EP – <name>"
        val name = info.name.orEmpty().replace(Regex("""^(Album|Álbum|EP|Single|Sencillo)\s*[–-]\s*""", RegexOption.IGNORE_CASE), "")
        val finalTracks = if (isAlbum && cover != null) tracks.map { it.copy(coverUrl = cover, album = name) } else tracks
        CollectionPage(
            title = name,
            subtitle = YouTubeIds.cleanArtist(info.uploaderName),
            coverUrl = cover,
            tracks = finalTracks.distinctBy { it.id },
            url = url,
        )
    }

    /** YouTube Music radio for a song (falls back to the video's related items). */
    suspend fun radio(videoId: String, limit: Int = 25): List<Track> = withContext(Dispatchers.IO) {
        radioCache[videoId]?.let { return@withContext it }
        val urls = listOf(
            "https://www.youtube.com/watch?v=$videoId&list=RDAMVM$videoId",
            "https://www.youtube.com/watch?v=$videoId&list=RD$videoId",
        )
        var result: List<Track> = emptyList()
        for (u in urls) {
            result = runCatchingCancellable { PlaylistInfo.getInfo(yt, u).relatedItems.mapNotNull { it.toTrack() } }
                .getOrDefault(emptyList())
                .filter { it.youtubeId != videoId }
            if (result.size >= 5) break
        }
        if (result.size < 5) {
            result = runCatchingCancellable {
                StreamInfo.getInfo(yt, YouTubeIds.watchUrl(videoId)).relatedItems.mapNotNull { (it as? StreamInfoItem)?.toTrack() }
            }.getOrDefault(result)
        }
        val out = result.distinctBy { it.id }.take(limit)
        if (out.isNotEmpty()) radioCache[videoId] = out
        out
    }

    /** Public playlists of a channel (used by "link account by @handle"). */
    suspend fun channelPlaylists(channelUrl: String): Pair<String, List<PlaylistRef>> = withContext(Dispatchers.IO) {
        val info = ChannelInfo.getInfo(yt, channelUrl)
        val lists = info.tabs.firstOrNull { tab -> tab.contentFilters.any { it == ChannelTabs.PLAYLISTS } }?.let { tab ->
            runCatchingCancellable { ChannelTabInfo.getInfo(yt, tab).relatedItems.mapNotNull { (it as? PlaylistInfoItem)?.toPlaylistRef() } }
                .getOrDefault(emptyList())
        }.orEmpty()
        info.name to lists
    }

    /** Metadata of one video (used for links shared to the app). */
    suspend fun trackInfo(videoId: String): Track? = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val info = StreamInfo.getInfo(yt, YouTubeIds.watchUrl(videoId))
            Track(
                id = Track.idForVideo(videoId),
                title = info.name ?: "",
                artist = YouTubeIds.cleanArtist(info.uploaderName ?: "").ifBlank { "YouTube" },
                durationSec = info.duration.toInt(),
                coverUrl = YouTubeIds.bestImage(info.thumbnails),
                videoId = videoId,
                artistUrl = info.uploaderUrl,
            )
        }.getOrNull()
    }

    // ------------------------------------------------------------------ mapping

    private fun StreamInfoItem.toTrack(): Track? {
        if (streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM) return null
        val vid = YouTubeIds.videoId(url) ?: return null
        val artist = YouTubeIds.cleanArtist(uploaderName).ifBlank { "YouTube" }
        return Track(
            id = Track.idForVideo(vid),
            title = name ?: "",
            artist = artist,
            durationSec = if (duration > 0) duration.toInt() else 0,
            coverUrl = YouTubeIds.bestImage(thumbnails),
            videoId = vid,
            artistUrl = uploaderUrl,
        )
    }

    private fun InfoItem.toTrack(): Track? = (this as? StreamInfoItem)?.toTrack()

    private fun ChannelInfoItem.toArtistRef() = ArtistRef(
        name = YouTubeIds.cleanArtist(name),
        url = url,
        imageUrl = YouTubeIds.bestImage(thumbnails),
        subtitle = if (subscriberCount > 0) "$subscriberCount" else null,
    )

    private fun PlaylistInfoItem.toAlbumRef(fallbackArtist: String = "") = AlbumRef(
        title = name ?: "",
        artist = YouTubeIds.cleanArtist(uploaderName).ifBlank { fallbackArtist },
        url = url,
        coverUrl = YouTubeIds.bestImage(thumbnails),
    )

    private fun PlaylistInfoItem.toPlaylistRef() = PlaylistRef(
        title = name ?: "",
        owner = YouTubeIds.cleanArtist(uploaderName),
        url = url,
        coverUrl = YouTubeIds.bestImage(thumbnails),
        count = streamCount,
    )

    companion object {
        fun normalize(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""\(.*?\)|\[.*?]"""), "")
            .replace(Regex("""\b(feat|ft|featuring)\b.*$"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
    }
}
