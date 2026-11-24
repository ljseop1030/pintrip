package com.pintrip.app.travel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TravelState(
    val isRunning: Boolean = false,
    val roomId: String? = null,
    val roomName: String? = null
)

object TravelModeState {
    private val _state = MutableStateFlow(TravelState())
    val state: StateFlow<TravelState> = _state.asStateFlow()

    fun updateState(isRunning: Boolean, roomId: String? = null, roomName: String? = null) {
        _state.value = TravelState(isRunning = isRunning, roomId = roomId, roomName = roomName)
    }

    fun stop() {
        _state.value = TravelState()
    }
}
