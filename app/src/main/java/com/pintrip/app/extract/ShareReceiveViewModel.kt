package com.pintrip.app.extract

import android.content.Intent
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.pintrip.app.R
import com.pintrip.app.data.TestConfig
import com.pintrip.app.ui.common.Event

enum class StepStatus {
    COMPLETED,
    IN_PROGRESS,
    PENDING,
    ERROR
}

data class ProgressStepsState(
    val step1Status: StepStatus = StepStatus.COMPLETED,
    val step2Status: StepStatus = StepStatus.IN_PROGRESS,
    val step3Status: StepStatus = StepStatus.IN_PROGRESS,
    val matchedCount: Int? = null,
)

sealed interface ShareNoticeEvent {
    data class ToastNotice(
        val messageResId: Int,
        val formatArgs: List<Any> = emptyList(),
    ) : ShareNoticeEvent
}

class ShareReceiveViewModel : ViewModel() {

    private val _shareInput = MutableLiveData<ShareInput>()
    val shareInput: LiveData<ShareInput> = _shareInput

    private val _stepsState = MutableLiveData(ProgressStepsState())
    val stepsState: LiveData<ProgressStepsState> = _stepsState

    private val _noticeEvent = MutableLiveData<Event<ShareNoticeEvent>>()
    val noticeEvent: LiveData<Event<ShareNoticeEvent>> = _noticeEvent

    val selectedRoomId: String = TestConfig.TEST_ROOM_ID

    fun processIntent(intent: Intent?) {
        if (_shareInput.value != null) return
        parseAndSetInput(intent)
    }

    fun processNewIntent(intent: Intent?) {
        parseAndSetInput(intent)
    }

    private fun parseAndSetInput(intent: Intent?) {
        val input = ShareIntentParser.parse(intent)
        _shareInput.value = input

        when (input) {
            is ShareInput.Screenshots -> {
                _stepsState.value = ProgressStepsState(
                    step1Status = StepStatus.COMPLETED,
                    step2Status = StepStatus.IN_PROGRESS,
                    step3Status = StepStatus.IN_PROGRESS,
                )

                if (input.originalCount > MAX_SCREENSHOTS) {
                    _noticeEvent.value = Event(
                        ShareNoticeEvent.ToastNotice(
                            messageResId = R.string.share_receive_notice_exceed_max_screenshots,
                            formatArgs = listOf(input.originalCount, MAX_SCREENSHOTS),
                        ),
                    )
                }
            }

            is ShareInput.LinkText -> {
                if (input.url != null) {
                    _stepsState.value = ProgressStepsState(
                        step1Status = StepStatus.COMPLETED,
                        step2Status = StepStatus.IN_PROGRESS,
                        step3Status = StepStatus.IN_PROGRESS,
                    )
                } else {
                    _stepsState.value = ProgressStepsState(
                        step1Status = StepStatus.ERROR,
                        step2Status = StepStatus.PENDING,
                        step3Status = StepStatus.PENDING,
                    )
                    _noticeEvent.value = Event(
                        ShareNoticeEvent.ToastNotice(
                            messageResId = R.string.share_receive_error_no_url,
                        )
                    )
                }
            }

            ShareInput.Empty -> {
                _stepsState.value = ProgressStepsState(
                    step1Status = StepStatus.ERROR,
                    step2Status = StepStatus.PENDING,
                    step3Status = StepStatus.PENDING,
                )
                _noticeEvent.value = Event(
                    ShareNoticeEvent.ToastNotice(
                        messageResId = R.string.share_receive_error_empty,
                    )
                )
            }
        }

        logShareInput(input)
    }

    fun updateStep2Status(status: StepStatus) {
        _stepsState.value = _stepsState.value?.copy(step2Status = status)
    }

    fun updateStep3Status(status: StepStatus, matchedCount: Int? = null) {
        _stepsState.value = _stepsState.value?.copy(
            step3Status = status,
            matchedCount = matchedCount,
        )
    }

    private fun logShareInput(input: ShareInput) {
        when (input) {
            is ShareInput.Screenshots -> {
                Log.d(TAG, "Parsed ShareInput.Screenshots (count: ${input.uris.size}, originalCount: ${input.originalCount}):")
                input.uris.forEachIndexed { index, uri ->
                    Log.d(TAG, "  [$index] $uri")
                }
            }

            is ShareInput.LinkText -> {
                Log.d(TAG, "Parsed ShareInput.LinkText (url: ${input.url}): ${input.text}")
            }

            ShareInput.Empty -> {
                Log.d(TAG, "Parsed ShareInput.Empty (No valid content found)")
            }
        }
    }

    companion object {
        private const val TAG = "SHARETEST"
        const val MAX_SCREENSHOTS = 5
    }
}
