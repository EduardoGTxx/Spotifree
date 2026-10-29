package com.spotifree.app.data.remote

import org.schabi.newpipe.extractor.Image

object YouTubeIds {
    private val VIDEO_ID = Regex("""(?:v=|youtu\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})""")
    private val BARE_ID = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val LIST_ID = Regex("""[?&]list=([A-Za-z0-9_-]+)""")
    private val CHANNEL_ID = Regex("""/channel/(UC[A-Za-z0-9_-]{22})""")

    fun videoId(urlOrId: String?): String? {
        if (urlOrId.isNullOrBlank()) return null
        val s = urlOrId.trim()
        if (BARE_ID.matches(s)) return s
        return VIDEO_ID.find(s)?.groupValues?.get(1)
    }

    fun playlistId(url: String?): String? = url?.let { LIST_ID.find(it)?.groupValues?.get(1) }

    fun channelId(url: String?): String? = url?.let { CHANNEL_ID.find(it)?.groupValues?.get(1) }

    fun watchUrl(videoId: String) = "https://music.youtube.com/watch?v=$videoId"

    fun playlistUrl(listId: String) = "https://www.youtube.com/playlist?list=$listId"

    /** Largest thumbnail, upgraded to a 544px square for YouTube Music artwork URLs. */
    fun bestImage(images: List<Image>?): String? {
        val best = images?.maxByOrNull { maxOf(it.height, it.width) }?.url ?: return null
        return upgradeArtwork(best)
    }

    fun upgradeArtwork(url: String?): String? {
        if (url == null) return null
        var u = url
        if (u.startsWith("//")) u = "https:$u"
        if (u.contains("googleusercontent.com") || u.contains("ggpht.com")) {
            u = u.replace(Regex("""=w\d+-h\d+[^&?]*$"""), "=w544-h544-l90-rj")
                .replace(Regex("""=s\d+[^&?]*$"""), "=s544")
        }
        if (u.contains("i.ytimg.com/vi")) {
            videoId(u)?.let { return "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            Regex("""/vi(?:_webp)?/([A-Za-z0-9_-]{11})/""").find(u)?.groupValues?.get(1)?.let {
                return "https://i.ytimg.com/vi/$it/hqdefault.jpg"
            }
        }
        return u
    }

    /** "Artist - Topic" channels and VEVO suffixes are noise in artist names. */
    fun cleanArtist(name: String?): String =
        (name ?: "").replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""VEVO$"""), "")
            .trim()
}
