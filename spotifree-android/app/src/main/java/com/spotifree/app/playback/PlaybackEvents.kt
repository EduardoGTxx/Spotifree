package com.spotifree.app.playback

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-process signals for the playback service (TGym rest periods). */
class PlaybackEvents {
    /** Music lowered for a rest period. */
    val duck = MutableStateFlow(false)

    /** When the current external (TGym) rest ends, epoch ms; 0 = none. */
    val restEndsAt = MutableStateFlow(0L)

    /** Play the "back to work" chime. */
    val chime = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}
