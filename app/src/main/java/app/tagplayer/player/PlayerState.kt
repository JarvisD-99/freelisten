package app.tagplayer.player

import kotlinx.coroutines.flow.MutableStateFlow

data class PlayerState(
    val queueIds: List<String> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val currentId: String? = null,
    val error: String? = null,
)

object PlaybackBus {
    val state = MutableStateFlow(PlayerState())
}
