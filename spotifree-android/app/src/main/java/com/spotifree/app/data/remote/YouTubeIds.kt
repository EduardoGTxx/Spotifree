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

    /**
     * Picks the best thumbnail, preferring square album artwork (`googleusercontent.com` or ~1:1 aspect ratio)
     * over rectangular 16:9 / 4:3 `i.ytimg.com` video frames, and upgrades it to 1200px HD.
     */
    fun bestImage(images: List<Image>?): String? {
        if (images.isNullOrEmpty()) return null
        val squareOrGoogle = images.filter { img ->
            val u = img.url.orEmpty()
            val isGoogleArt = u.contains("googleusercontent.com") || u.contains("ggpht.com")
            val isSquare = img.width > 0 && img.height > 0 && (img.width.toFloat() / img.height.toFloat()) in 0.92f..1.08f
            isGoogleArt || isSquare
        }
        val pool = squareOrGoogle.ifEmpty { images }
        val best = pool.maxByOrNull { maxOf(it.height, it.width) }?.url ?: return null
        return upgradeArtwork(best)
    }

    fun upgradeArtwork(url: String?, size: Int = 1200): String? {
        if (url.isNullOrBlank()) return null
        var u = url.trim()
        if (u.startsWith("//")) u = "https:$u"
        else if (u.startsWith("http://")) u = "https://" + u.removePrefix("http://")
        if (u.contains("googleusercontent.com") || u.contains("ggpht.com") || u.contains("music.youtube.com")) {
            u = if (Regex("""=[^/?#]+$""").containsMatchIn(u)) {
                u.replace(Regex("""=[^/?#]+$"""), "=w$size-h$size-l90-rj")
            } else {
                u.replace(Regex("""=w\d+-h\d+[^&?]*"""), "=w$size-h$size-l90-rj")
                    .replace(Regex("""=s\d+[^&?]*"""), "=s$size")
            }
            return u
        }
        if (u.contains("mzstatic.com")) {
            val target = minOf(size, 1000)
            return u.replace(Regex("""/\d+x\d+bb(?:-\d+)?\.(jpg|png|webp)""", RegexOption.IGNORE_CASE), "/${target}x${target}bb.jpg")
                .replace(Regex("""/\d+x\d+bb\.""", RegexOption.IGNORE_CASE), "/${target}x${target}bb.")
        }
        if (u.contains("i.ytimg.com/vi") || u.contains("img.youtube.com/vi")) {
            videoId(u)?.let { return "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            Regex("""/vi(?:_webp)?/([A-Za-z0-9_-]{11})/""").find(u)?.groupValues?.get(1)?.let {
                return "https://i.ytimg.com/vi/$it/hqdefault.jpg"
            }
        }
        return u
    }

    /** True when the cover is missing, a placeholder, or a raw YouTube video frame (`ytimg.com`) rather than square album art. */
    fun isLowResVideoThumbnail(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val lower = url.lowercase()
        if (lower.contains("ytimg.com") || lower.contains("img.youtube.com") || lower.contains("unsplash.com")) return true
        if (lower.startsWith("data:image/svg")) return true
        return false
    }

    /** "Artist - Topic" channels and VEVO suffixes are noise in artist names. */
    fun cleanArtist(name: String?): String =
        (name ?: "")
            .replace(Regex("""\s*-\s*Topic\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\bVEVO\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(?:official|oficial)\s*(?:channel|canal)?\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\((?:official|oficial)\)\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
}
