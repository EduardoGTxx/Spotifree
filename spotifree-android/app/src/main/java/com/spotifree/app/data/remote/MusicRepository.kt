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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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
 * Everything the app reads from YouTube / YouTube Music, through NewPipe Extractor
 * and YouTube Music's WEB_REMIX radio endpoint:
 * search, suggestions, artists, albums, playlists and dynamic radio (YouTube Music Mix).
 */
class MusicRepository(private val http: OkHttpClient = OkHttpClient()) {
    private val yt get() = ServiceList.YouTube

    private val searchCache = object : LinkedHashMap<String, SearchResults>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchResults>?) = size > 40
    }
    private val radioPoolCache = ConcurrentHashMap<String, List<Track>>()

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
            val nativeScore = runCatching {
                uniffi.spotifree_core_ffi.fuzzyMatchTrack(
                    targetArtist = artist,
                    targetTitle = title,
                    targetDurationSec = durationSec,
                    candArtist = c.artist,
                    candTitle = c.title,
                    candDurationSec = c.durationSec,
                )
            }.getOrNull()
            if (nativeScore != null) {
                return@maxByOrNull nativeScore
            }

            var score = 0f
            val ct = normalize(c.title)
            val ca = normalize(c.artist)
            if (ct == nTitle) score += 6f else if (nTitle.isNotEmpty() && (ct.contains(nTitle) || nTitle.contains(ct))) score += 3f
            if (ca == nArtist) score += 4f else if (nArtist.isNotEmpty() && (ca.contains(nArtist) || nArtist.contains(ca))) score += 2f
            if (durationSec > 0 && c.durationSec > 0) {
                val diff = kotlin.math.abs(c.durationSec - durationSec)
                score += when {
                    diff <= 3 -> 4f
                    diff <= 10 -> 2f
                    diff > 40 -> -4f
                    else -> 0f
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

    /**
     * Dynamic YouTube Music radio for a song.
     * Primary source: YouTube Music WEB_REMIX `/youtubei/v1/next` (`RDAMVM<videoId>`), which returns
     * ~50 tracks matching the seed song's genre, era and language.
     * The raw 50-track pool is cached, and each call dynamically samples across similarity tiers
     * (closest -> mid -> exploratory) with artist-spacing so the queue is never static or repetitive.
     */
    suspend fun radio(videoId: String, limit: Int = 25, seedTrack: Track? = null): List<Track> = withContext(Dispatchers.IO) {
        val rawPool = radioPoolCache[videoId] ?: run {
            var fetched = runCatchingCancellable { fetchWebRemixRadio(videoId) }.getOrDefault(emptyList())
                .filter { it.youtubeId != videoId }

            if (fetched.size < 5) {
                val urls = listOf(
                    "https://www.youtube.com/watch?v=$videoId&list=RDAMVM$videoId",
                    "https://www.youtube.com/watch?v=$videoId&list=RD$videoId",
                )
                for (u in urls) {
                    val np = runCatchingCancellable { PlaylistInfo.getInfo(yt, u).relatedItems.mapNotNull { it.toTrack() } }
                        .getOrDefault(emptyList())
                        .filter { it.youtubeId != videoId }
                    if (np.size >= 5) {
                        fetched = np
                        break
                    }
                }
            }

            if (fetched.size < 5 && seedTrack != null && seedTrack.artist.isNotBlank()) {
                val byArtistAndTitle = runCatchingCancellable {
                    searchItems("${seedTrack.artist} ${seedTrack.title}", YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
                        .mapNotNull { (it as? StreamInfoItem)?.toTrack() }
                }.getOrDefault(emptyList())
                val byArtist = runCatchingCancellable {
                    searchItems(seedTrack.artist, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
                        .mapNotNull { (it as? StreamInfoItem)?.toTrack() }
                }.getOrDefault(emptyList())
                val combined = (byArtistAndTitle + byArtist).filter { it.youtubeId != videoId }
                if (combined.size >= 5) fetched = combined
            }

            if (fetched.size < 5) {
                fetched = runCatchingCancellable {
                    StreamInfo.getInfo(yt, YouTubeIds.watchUrl(videoId)).relatedItems.mapNotNull { (it as? StreamInfoItem)?.toTrack() }
                }.getOrDefault(fetched)
                    .filter { it.youtubeId != videoId }
            }

            val deduped = deduplicateTracks(fetched)
            if (deduped.isNotEmpty()) radioPoolCache[videoId] = deduped
            deduped
        }
        sampleDynamicRadio(rawPool, limit)
    }

    /**
     * Multi-seed dynamic radio for playlists (including multi-language playlists) and evolving radio waves.
     * Samples up to 3 diverse artist seeds, fetches their YouTube Music radio pools in parallel,
     * interleaves them round-robin, and gradually bridges into [bridgeTracks] at the tail / later waves.
     */
    suspend fun multiSeedRadio(
        seeds: List<Track>,
        excludeIds: Set<String> = emptySet(),
        bridgeTracks: List<Track> = emptyList(),
        wave: Int = 0,
        limit: Int = 20,
    ): List<Track> = withContext(Dispatchers.IO) {
        val chosenSeeds = pickDiverseSeeds(seeds.filter { it.youtubeId != null }, maxSeeds = 3)
        if (chosenSeeds.isEmpty()) return@withContext emptyList()

        val perSeedLists = coroutineScope {
            chosenSeeds.map { seed ->
                async {
                    val vid = seed.youtubeId ?: return@async emptyList()
                    runCatchingCancellable { radio(vid, limit = 25, seedTrack = seed) }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }

        val interleaved = interleaveLists(perSeedLists)
            .filter { it.id !in excludeIds }
        val mainPool = spreadArtists(deduplicateTracks(interleaved))

        val bridgePool = bridgeTracks
            .filter { it.id !in excludeIds && mainPool.none { m -> m.id == it.id } }
            .shuffled()
        val bridgeCount = when {
            bridgePool.isEmpty() -> 0
            wave <= 0 -> minOf(2, (limit * 0.12).toInt().coerceAtLeast(1), bridgePool.size)
            else -> minOf(4, (limit * 0.25).toInt().coerceAtLeast(2), bridgePool.size)
        }

        if (bridgeCount == 0) {
            mainPool.take(limit)
        } else {
            val coreCount = (limit - bridgeCount).coerceAtLeast(1)
            val core = mainPool.take(coreCount).toMutableList()
            val bridges = bridgePool.take(bridgeCount)
            if (wave <= 0 || core.size < 4) {
                // First wave: keep the first ~85-90% strictly in the seed's style/language,
                // then introduce 1-2 bridge tracks near the very end ("tarde o temprano").
                val insertPos = (core.size - 2).coerceAtLeast(1)
                core.addAll(insertPos, bridges.take(1))
                core.addAll(bridges.drop(1))
            } else {
                // Subsequent waves: weave bridge tracks across the second half of the wave.
                val startIdx = (core.size / 2).coerceAtLeast(1)
                bridges.forEachIndexed { idx, b ->
                    val pos = (startIdx + idx * 3).coerceAtMost(core.size)
                    core.add(pos, b)
                }
            }
            core.distinctBy { it.id }.take(limit)
        }
    }

    private fun fetchWebRemixRadio(videoId: String): List<Track> {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", "1.20250310.01.00")
                .put("hl", "en")
                .put("gl", "US")))
            .put("videoId", videoId)
            .put("playlistId", "RDAMVM$videoId")
            .put("params", "wAEB")
            .put("isAudioOnly", true)
            .put("enablePersistentPlaylistPanel", true)
            .toString()

        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/next?prettyPrint=false")
            .header("User-Agent", WEB_REMIX_UA)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", "1.20250310.01.00")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val raw = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string() ?: return emptyList()
        }
        val root = JSONObject(raw)
        val watchNext = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
        val panelContents = watchNext
            ?.optJSONObject("tabbedRenderer")
            ?.optJSONObject("watchNextTabbedResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicQueueRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("playlistPanelRenderer")
            ?.optJSONArray("contents")
            ?: watchNext
                ?.optJSONObject("playlist")
                ?.optJSONObject("playlist")
                ?.optJSONArray("contents")
            ?: return emptyList()

        val out = ArrayList<Track>(panelContents.length())
        for (i in 0 until panelContents.length()) {
            val itemObj = panelContents.optJSONObject(i) ?: continue
            val renderer = itemObj.optJSONObject("playlistPanelVideoRenderer")
                ?: itemObj.optJSONObject("playlistPanelVideoWrapperRenderer")
                    ?.optJSONObject("primaryRenderer")
                    ?.optJSONObject("playlistPanelVideoRenderer")
                ?: continue
            parsePanelVideoRenderer(renderer)?.let { out += it }
        }
        return out
    }

    private fun parsePanelVideoRenderer(renderer: JSONObject): Track? {
        val vid = renderer.optString("videoId").takeIf { it.isNotBlank() } ?: return null
        val rawTitle = runsText(renderer.optJSONObject("title")).ifBlank { return null }
        val title = cleanTrackTitle(rawTitle)

        var artist = ""
        var album = ""
        var artistUrl: String? = null
        val bylineRuns = renderer.optJSONObject("longBylineText")?.optJSONArray("runs")
            ?: renderer.optJSONObject("shortBylineText")?.optJSONArray("runs")
        if (bylineRuns != null) {
            val segments = mutableListOf<StringBuilder>()
            var current = StringBuilder()
            for (i in 0 until bylineRuns.length()) {
                val r = bylineRuns.optJSONObject(i) ?: continue
                val text = r.optString("text")
                if (text.contains("•") || text.contains("·")) {
                    segments += current
                    current = StringBuilder()
                } else {
                    current.append(text)
                    if (segments.isEmpty() && artistUrl == null) {
                        val browseId = r.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("browseEndpoint")
                            ?.optString("browseId")
                            ?.takeIf { it.startsWith("UC") }
                        if (browseId != null) artistUrl = "https://www.youtube.com/channel/$browseId"
                    }
                }
            }
            segments += current
            artist = YouTubeIds.cleanArtist(segments.firstOrNull()?.toString().orEmpty())
            val second = segments.getOrNull(1)?.toString()?.trim().orEmpty()
            if (second.isNotBlank() && !second.matches(Regex("""^\d{4}$""")) && !second.contains("views", ignoreCase = true) && !second.contains("vistas", ignoreCase = true)) {
                album = second
            }
        }
        if (artist.isBlank()) artist = "YouTube Music"

        val durationSec = parseDurationText(runsText(renderer.optJSONObject("lengthText")))
        val coverUrl = bestThumbnail(renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
        return Track(
            id = Track.idForVideo(vid),
            title = title,
            artist = artist,
            album = album,
            durationSec = durationSec,
            coverUrl = coverUrl,
            videoId = vid,
            artistUrl = artistUrl,
        )
    }

    private fun runsText(obj: JSONObject?): String {
        val runs = obj?.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString().trim()
    }

    private fun parseDurationText(text: String): Int {
        if (text.isBlank()) return 0
        val parts = text.trim().split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    private fun bestThumbnail(arr: JSONArray?): String? {
        if (arr == null || arr.length() == 0) return null
        var bestSquareUrl: String? = null
        var bestSquareArea = -1
        var bestAnyUrl: String? = null
        var bestAnyArea = -1
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val url = t.optString("url").takeIf { it.isNotBlank() } ?: continue
            val w = t.optInt("width", 0)
            val h = t.optInt("height", 0)
            val area = w * h
            val isSquare = (w > 0 && h > 0 && (w.toFloat() / h.toFloat()) in 0.92f..1.08f) ||
                url.contains("googleusercontent.com") || url.contains("ggpht.com")
            if (isSquare && area >= bestSquareArea) {
                bestSquareArea = area
                bestSquareUrl = url
            }
            if (area >= bestAnyArea) {
                bestAnyArea = area
                bestAnyUrl = url
            }
        }
        return YouTubeIds.upgradeArtwork(bestSquareUrl ?: bestAnyUrl)
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
        private const val WEB_REMIX_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

        fun normalize(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""\(.*?\)|\[.*?]"""), "")
            .replace(Regex("""\b(feat|ft|featuring)\b.*$"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()

        /** Removes noisy video suffixes like "(Official Music Video)" while preserving "(Remix)" or "(Live)". */
        fun cleanTrackTitle(raw: String): String = raw
            .replace(
                Regex(
                    """\s*[\(\[]\s*(official\s*(music\s*)?video|official\s*audio|official\s*lyric\s*video|lyric\s*video|video\s*oficial|audio\s*oficial|visualizer|hd|4k|remastered\s*\d*)\s*[\)\]]""",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .trim()
            .ifBlank { raw.trim() }

        /** Deduplicates by track ID and normalized (artist + title) key. */
        fun deduplicateTracks(tracks: List<Track>): List<Track> {
            val seenIds = HashSet<String>()
            val seenKeys = HashSet<String>()
            val out = ArrayList<Track>(tracks.size)
            for (t in tracks) {
                if (!seenIds.add(t.id)) continue
                val key = "${normalize(t.artist)}|${normalize(t.title)}"
                if (key != "|" && !seenKeys.add(key)) continue
                out += t
            }
            return out
        }

        /**
         * Dynamically samples a radio pool across 3 similarity tiers:
         * - Tier 1 (indices 0..13): closest style, era & language to the seed
         * - Tier 2 (indices 14..33): mid-range similarity
         * - Tier 3 (indices 34..end): broader / exploratory tracks
         * Each tier is shuffled on every call so the queue is dynamic rather than static.
         */
        fun sampleDynamicRadio(pool: List<Track>, limit: Int): List<Track> {
            if (pool.isEmpty() || limit <= 0) return emptyList()
            if (pool.size <= 6) return spreadArtists(pool.shuffled()).take(limit)

            val tier1End = minOf(14, pool.size)
            val tier2End = minOf(34, pool.size)

            val tier1 = pool.subList(0, tier1End).shuffled()
            val tier2 = if (pool.size > tier1End) pool.subList(tier1End, tier2End).shuffled() else emptyList()
            val tier3 = if (pool.size > tier2End) pool.subList(tier2End, pool.size).shuffled() else emptyList()

            val quota1 = (limit * 0.55).toInt().coerceAtLeast(1)
            val quota2 = (limit * 0.30).toInt().coerceAtLeast(1)
            val quota3 = (limit - quota1 - quota2).coerceAtLeast(1)

            val picked = ArrayList<Track>(limit)
            picked += tier1.take(quota1)
            picked += tier2.take(quota2)
            picked += tier3.take(quota3)

            if (picked.size < limit) {
                val pickedIds = picked.map { it.id }.toHashSet()
                val leftovers = (tier1 + tier2 + tier3).filter { it.id !in pickedIds }
                picked += leftovers.take(limit - picked.size)
            }
            return spreadArtists(picked).take(limit)
        }

        /** Reorders tracks greedily so the same primary artist never plays back-to-back when avoidable. */
        fun spreadArtists(tracks: List<Track>): List<Track> {
            if (tracks.size <= 2) return tracks
            val remaining = tracks.toMutableList()
            val result = ArrayList<Track>(tracks.size)
            var lastArtist = ""
            while (remaining.isNotEmpty()) {
                val idx = remaining.indexOfFirst { primaryArtistKey(it.artist) != lastArtist }
                    .takeIf { it >= 0 } ?: 0
                val chosen = remaining.removeAt(idx)
                result += chosen
                lastArtist = primaryArtistKey(chosen.artist)
            }
            return result
        }

        /** Picks up to [maxSeeds] tracks with distinct primary artists, keeping the first seed when present. */
        fun pickDiverseSeeds(seeds: List<Track>, maxSeeds: Int = 3): List<Track> {
            if (seeds.isEmpty() || maxSeeds <= 0) return emptyList()
            val chosen = ArrayList<Track>(maxSeeds)
            val seenArtists = HashSet<String>()
            val first = seeds.first()
            chosen += first
            seenArtists += primaryArtistKey(first.artist)

            val rest = seeds.drop(1).shuffled()
            for (candidate in rest) {
                if (chosen.size >= maxSeeds) break
                val artistKey = primaryArtistKey(candidate.artist)
                if (artistKey.isNotBlank() && seenArtists.add(artistKey) && chosen.none { it.id == candidate.id }) {
                    chosen += candidate
                }
            }
            if (chosen.size < maxSeeds) {
                for (candidate in rest) {
                    if (chosen.size >= maxSeeds) break
                    if (chosen.none { it.id == candidate.id }) chosen += candidate
                }
            }
            return chosen
        }

        /** Round-robin interleaving across multiple seed track lists. */
        fun <T> interleaveLists(lists: List<List<T>>): List<T> {
            val maxSize = lists.maxOfOrNull { it.size } ?: return emptyList()
            val out = ArrayList<T>(lists.sumOf { it.size })
            for (i in 0 until maxSize) {
                for (list in lists) {
                    if (i < list.size) out += list[i]
                }
            }
            return out
        }

        private fun primaryArtistKey(artist: String): String =
            normalize(artist.split(Regex(""",|&| x | feat\.? """, RegexOption.IGNORE_CASE)).firstOrNull().orEmpty())
    }
}
