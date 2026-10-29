package com.spotifree.app.ui.common

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A short non-blocking notice (snackbar), optionally with one action such as "Undo". */
data class UiMessage(
    val text: String,
    val actionLabel: String? = null,
    val isError: Boolean = false,
    val long: Boolean = false,
    val action: (() -> Unit)? = null,
)

/** App-wide notices: any repository or screen can post, MainActivity shows them. */
class Messages {
    private val _flow = MutableSharedFlow<UiMessage>(extraBufferCapacity = 16)
    val flow: SharedFlow<UiMessage> = _flow.asSharedFlow()

    fun post(text: String, isError: Boolean = false) {
        _flow.tryEmit(UiMessage(text, isError = isError))
    }

    fun post(message: UiMessage) {
        _flow.tryEmit(message)
    }
}
