package com.pintrip.app.room

import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import com.pintrip.app.R
import com.pintrip.app.data.model.Room
import com.pintrip.app.data.repository.RoomRepository
import com.pintrip.app.ui.common.Event
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class CreateRoomViewModel(
    private val roomRepository: RoomRepository = RoomRepository(),
) : ViewModel() {

    private val _selectedStartDate = MutableLiveData<Timestamp?>()
    val selectedStartDate: LiveData<Timestamp?> = _selectedStartDate

    private val _selectedEndDate = MutableLiveData<Timestamp?>()
    val selectedEndDate: LiveData<Timestamp?> = _selectedEndDate

    private val _selectedIcon = MutableLiveData<String>("sea")
    val selectedIcon: LiveData<String> = _selectedIcon

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _createSuccessEvent = MutableLiveData<Event<Room>>()
    val createSuccessEvent: LiveData<Event<Room>> = _createSuccessEvent

    private val _errorMessageEvent = MutableLiveData<Event<Int>>()
    val errorMessageEvent: LiveData<Event<Int>> = _errorMessageEvent

    fun setIcon(icon: String) {
        if (icon in RoomRepository.VALID_ICONS) {
            _selectedIcon.value = icon
        }
    }

    fun setDates(startDate: Timestamp?, endDate: Timestamp?) {
        _selectedStartDate.value = startDate
        _selectedEndDate.value = endDate
    }

    fun clearDates() {
        _selectedStartDate.value = null
        _selectedEndDate.value = null
    }

    fun createRoom(name: String, region: String) {
        if (_isLoading.value == true) return

        val trimmedName = name.trim()
        val trimmedRegion = region.trim()

        if (trimmedName.isEmpty()) {
            _errorMessageEvent.value = Event(R.string.error_empty_room_name)
            return
        }

        if (trimmedRegion.isEmpty()) {
            _errorMessageEvent.value = Event(R.string.error_empty_region)
            return
        }

        val start = _selectedStartDate.value
        val end = _selectedEndDate.value
        if (start != null && end != null && end.compareTo(start) < 0) {
            _errorMessageEvent.value = Event(R.string.error_invalid_date_range)
            return
        }

        _isLoading.value = true

        viewModelScope.launch {
            try {
                val createdRoom = roomRepository.createRoom(
                    name = trimmedName,
                    region = trimmedRegion,
                    startDate = start,
                    endDate = end,
                    icon = _selectedIcon.value ?: "sea",
                )
                _createSuccessEvent.value = Event(createdRoom)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FirebaseFirestoreException) {
                _isLoading.value = false
                @StringRes val errorResId = when (e.code) {
                    FirebaseFirestoreException.Code.PERMISSION_DENIED -> R.string.error_create_room_permission_denied
                    FirebaseFirestoreException.Code.UNAVAILABLE -> R.string.error_network_unavailable
                    else -> R.string.error_create_room_default
                }
                _errorMessageEvent.value = Event(errorResId)
            } catch (_: Exception) {
                _isLoading.value = false
                _errorMessageEvent.value = Event(R.string.error_create_room_default)
            }
        }
    }
}
