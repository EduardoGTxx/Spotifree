package com.spotifree.app.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Shuffle that reorders the queue itself (like the previous app): the current song stays, the
 * rest is shuffled after it and the original order is remembered for when shuffle is turned off.
 * With ExoPlayer's built-in shuffle the visible queue never matched what played next and
 * "Play next" landed in a random spot.
 */
class ShufflingPlayer(private val exo: ExoPlayer) : ForwardingPlayer(exo) {
    private var shuffled = false
    private var originalOrder: List<String>? = null
    private val listeners = LinkedHashSet<Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        listeners += listener
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners -= listener
        super.removeListener(listener)
    }

    override fun getShuffleModeEnabled(): Boolean = shuffled

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        if (shuffleModeEnabled == shuffled) return
        if (exo.mediaItemCount > 1) {
            if (shuffleModeEnabled) {
                originalOrder = ids()
                val current = exo.currentMediaItemIndex
                val rest = (0 until exo.mediaItemCount).filter { it != current }.map { exo.getMediaItemAt(it) }.shuffled()
                keepCurrentAndReplaceRest(rest)
            } else {
                val order = originalOrder
                if (order != null) {
                    val byId = LinkedHashMap<String, MediaItem>()
                    for (i in 0 until exo.mediaItemCount) byId[exo.getMediaItemAt(i).mediaId] = exo.getMediaItemAt(i)
                    val restored = order.mapNotNull { byId.remove(it) } + byId.values // songs added while shuffled go last
                    val currentId = exo.currentMediaItem?.mediaId
                    val currentPos = restored.indexOfFirst { it.mediaId == currentId }.coerceAtLeast(0)
                    keepCurrentAndReplaceRest(restored.filterIndexed { i, _ -> i != currentPos }, before = currentPos)
                }
            }
        }
        shuffled = shuffleModeEnabled
        if (!shuffled) originalOrder = null
        listeners.toList().forEach { it.onShuffleModeEnabledChanged(shuffled) }
    }

    // A new queue starts unshuffled (the caller turns shuffle on afterwards when wanted)
    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        resetShuffle()
        super.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        resetShuffle()
        super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
        resetShuffle()
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        resetShuffle()
        super.setMediaItem(mediaItem)
    }

    private fun resetShuffle() {
        if (!shuffled) return
        shuffled = false
        originalOrder = null
        listeners.toList().forEach { it.onShuffleModeEnabledChanged(false) }
    }

    /** Restores state saved with the queue (after the queue itself was restored). */
    fun restore(shuffle: Boolean, original: List<String>?) {
        shuffled = shuffle
        originalOrder = if (shuffle) original else null
    }

    fun originalOrder(): List<String>? = originalOrder

    private fun ids(): List<String> = (0 until exo.mediaItemCount).map { exo.getMediaItemAt(it).mediaId }

    /**
     * Rebuilds the queue around the playing item without interrupting it.
     * @param rest the other items in their new order
     * @param before how many of [rest] go before the current item
     */
    private fun keepCurrentAndReplaceRest(rest: List<MediaItem>, before: Int = 0) {
        val current = exo.currentMediaItemIndex
        val count = exo.mediaItemCount
        if (current + 1 < count) exo.removeMediaItems(current + 1, count)
        if (current > 0) exo.removeMediaItems(0, current)
        val head = rest.take(before)
        val tail = rest.drop(before)
        if (tail.isNotEmpty()) exo.addMediaItems(1, tail)
        if (head.isNotEmpty()) exo.addMediaItems(0, head)
    }
}
