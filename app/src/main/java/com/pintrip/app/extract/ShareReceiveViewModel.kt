package com.pintrip.app.extract

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.pintrip.app.R
import com.pintrip.app.data.TestConfig
import com.pintrip.app.data.model.GeminiExtractionResponse
import com.pintrip.app.data.remote.GeminiExtractor
import com.pintrip.app.ui.common.Event
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

enum class StepStatus {
    COMPLETED,
    IN_PROGRESS,
    PENDING,
    ERROR
}

data class ProgressStepsState(
    val step1Status: StepStatus = StepStatus.COMPLETED,
    val step2Status: StepStatus = StepStatus.PENDING,
    val step3Status: StepStatus = StepStatus.PENDING,
    val matchedCount: Int? = null,
)

data class NavigateToExtractArgs(
    val roomId: String,
    val shareInput: ShareInput,
)

data class NavigateToSelectPlacesArgs(
    val roomId: String,
    val matchResults: List<MatchResult>,
)

sealed interface ShareNoticeEvent {
    data class ToastNotice(
        val messageResId: Int,
        val formatArgs: List<Any> = emptyList(),
    ) : ShareNoticeEvent
}

class ShareReceiveViewModel(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val geminiExtractorFactory: (Context) -> GeminiExtractor = { GeminiExtractor(it) },
    private val placeMatcherFactory: () -> PlaceMatcher = { PlaceMatcher() },
) : ViewModel() {

    private val _shareInput = MutableLiveData<ShareInput>()
    val shareInput: LiveData<ShareInput> = _shareInput

    private val _stepsState = MutableLiveData(ProgressStepsState())
    val stepsState: LiveData<ProgressStepsState> = _stepsState

    private val _noticeEvent = MutableLiveData<Event<ShareNoticeEvent>>()
    val noticeEvent: LiveData<Event<ShareNoticeEvent>> = _noticeEvent

    private val _navigateToExtract = MutableLiveData<Event<NavigateToExtractArgs>>()
    val navigateToExtract: LiveData<Event<NavigateToExtractArgs>> = _navigateToExtract

    private val _navigateToSelectPlaces = MutableLiveData<Event<NavigateToSelectPlacesArgs>>()
    val navigateToSelectPlaces: LiveData<Event<NavigateToSelectPlacesArgs>> = _navigateToSelectPlaces

    private val _matchResults = MutableLiveData<List<MatchResult>>()
    val matchResults: LiveData<List<MatchResult>> = _matchResults

    val selectedRoomId: String = TestConfig.TEST_ROOM_ID

    private var isProceeded = false

    fun processIntent(intent: Intent?) {
        if (_shareInput.value != null) return
        parseAndSetInput(intent)
    }

    fun processNewIntent(intent: Intent?) {
        isProceeded = false
        parseAndSetInput(intent)
    }

    fun onCheckPlacesClicked() {
        if (isProceeded) return

        val input = _shareInput.value ?: return
        if (input is ShareInput.Empty) return
        if ((input is ShareInput.LinkText) && (input.url == null)) return

        isProceeded = true

        _stepsState.value = _stepsState.value?.copy(
            step2Status = StepStatus.IN_PROGRESS,
            step3Status = StepStatus.PENDING,
        )

        Log.d(TAG, "onCheckPlacesClicked: Connecting to next step with roomId=$selectedRoomId, input=$input")
        _navigateToExtract.value = Event(
            NavigateToExtractArgs(
                roomId = selectedRoomId,
                shareInput = input,
            ),
        )
    }

    private fun parseAndSetInput(intent: Intent?) {
        val input = ShareIntentParser.parse(intent)
        _shareInput.value = input

        when (input) {
            is ShareInput.Screenshots -> {
                _stepsState.value = ProgressStepsState(
                    step1Status = StepStatus.COMPLETED,
                    step2Status = StepStatus.PENDING,
                    step3Status = StepStatus.PENDING,
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
                        step2Status = StepStatus.PENDING,
                        step3Status = StepStatus.PENDING,
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
                        ),
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
                    ),
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

    fun startExtractStep(context: Context, roomId: String, shareInput: ShareInput) {
        Log.d(TAG, "startExtractStep called: roomId=$roomId, shareInput=$shareInput")

        if (shareInput !is ShareInput.Screenshots) {
            Log.w(TAG, "Unsupported shareInput type for extraction: $shareInput")
            isProceeded = false
            updateStep2Status(StepStatus.ERROR)
            updateStep3Status(StepStatus.ERROR)
            _noticeEvent.value = Event(
                ShareNoticeEvent.ToastNotice(
                    messageResId = R.string.share_receive_error_could_not_find_places,
                ),
            )
            // TODO: Temporary handling - manual search button to be added later (docs/data-contract.md §8)
            return
        }

        val appContext = context.applicationContext

        viewModelScope.launch {
            try {
                // Step 2 시작: 텍스트 추출 진행 중, Step 3 대기
                updateStep2Status(StepStatus.IN_PROGRESS)
                updateStep3Status(StepStatus.PENDING)

                // 1. 방 정보(지역명) 조회
                val roomSnapshot = db.collection("rooms")
                    .document(roomId)
                    .get()
                    .await()

                val roomRegion = roomSnapshot.getString("region")?.trim().orEmpty()
                Log.d(TAG, "Fetched room region for roomId=$roomId: '$roomRegion'")

                if (roomRegion.isBlank()) {
                    Log.w(TAG, "Room region is blank for roomId=$roomId")
                    isProceeded = false
                    updateStep2Status(StepStatus.ERROR)
                    updateStep3Status(StepStatus.ERROR)
                    _noticeEvent.value = Event(
                        ShareNoticeEvent.ToastNotice(
                            messageResId = R.string.share_receive_error_could_not_find_places,
                        ),
                    )
                    // TODO: Temporary handling - manual search button to be added later (docs/data-contract.md §8)
                    return@launch
                }

                // 2. 공유된 스크린샷마다 Gemini 추출 수행 (docs/data-contract.md §8 규격: 실패 시 1회 재시도)
                val geminiExtractor = geminiExtractorFactory(appContext)
                val extractionResponses = mutableListOf<GeminiExtractionResponse>()

                for (uri in shareInput.uris) {
                    var response: GeminiExtractionResponse? = null
                    var attempt = 0
                    while (attempt < 2 && response == null) {
                        try {
                            attempt++
                            Log.d(TAG, "Extracting places from screenshot URI (attempt $attempt): $uri")
                            response = geminiExtractor.extractFromScreenshot(uri, roomRegion)
                        } catch (e: Exception) {
                            Log.w(TAG, "Gemini extraction attempt $attempt failed for URI $uri: ${e.message}", e)
                            if (attempt >= 2) {
                                throw e
                            }
                        }
                    }
                    if (response != null) {
                        extractionResponses.add(response)
                    }
                }

                // Step 2 완료: 텍스트 추출 완료
                updateStep2Status(StepStatus.COMPLETED)
                Log.d(TAG, "Gemini extraction completed. Responses count: ${extractionResponses.size}")

                val totalPlacesExtracted = extractionResponses.sumOf { it.places.size }
                if (totalPlacesExtracted == 0) {
                    // Gemini가 추출한 장소가 0개인 경우 (docs/data-contract.md §8)
                    isProceeded = false
                    updateStep3Status(StepStatus.ERROR)
                    _noticeEvent.value = Event(
                        ShareNoticeEvent.ToastNotice(
                            messageResId = R.string.share_receive_error_could_not_find_places,
                        ),
                    )
                    // TODO: Temporary handling - manual search button to be added later (docs/data-contract.md §8)
                    return@launch
                }

                // Step 3 시작: 장소 매칭 진행 중
                updateStep3Status(StepStatus.IN_PROGRESS)

                // 3. 추출된 결과를 PlaceMatcher에 전달하여 카카오 장소 매칭 수행
                val placeMatcher = placeMatcherFactory()
                val matchResults = placeMatcher.matchPlaces(extractionResponses, roomRegion)

                // NOT_FOUND를 제외하고 카카오 매칭이 완료된 유효 장소 개수 계산
                val validMatchedCount = matchResults.count { it.status != MatchResult.STATUS_NOT_FOUND }

                if (validMatchedCount == 0) {
                    // 카카오 매칭 유효 결과가 0개인 경우 (docs/data-contract.md §8)
                    isProceeded = false
                    updateStep3Status(StepStatus.ERROR)
                    _noticeEvent.value = Event(
                        ShareNoticeEvent.ToastNotice(
                            messageResId = R.string.share_receive_error_could_not_find_places,
                        ),
                    )
                    // TODO: Temporary handling - manual search button to be added later (docs/data-contract.md §8)
                    return@launch
                }

                _matchResults.value = matchResults
                updateStep3Status(StepStatus.COMPLETED, matchedCount = validMatchedCount)
                Log.d(TAG, "Place matching completed. Valid matched count: $validMatchedCount, total results: ${matchResults.size}")

                // 5개 초과 안내 처리 (docs/data-contract.md §8)
                val mergedResponse = mergeGeminiExtractionResponses(extractionResponses)
                if (mergedResponse.hasMore) {
                    _noticeEvent.value = Event(
                        ShareNoticeEvent.ToastNotice(
                            messageResId = R.string.share_receive_notice_up_to_5_places,
                        ),
                    )
                }

                // 다음 화면(장소 선택)으로 전달할 결과 이벤트 발행
                _navigateToSelectPlaces.value = Event(
                    NavigateToSelectPlacesArgs(
                        roomId = roomId,
                        matchResults = matchResults,
                    ),
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error during extract step after retry: ${e.message}", e)
                isProceeded = false
                updateStep2Status(StepStatus.ERROR)
                updateStep3Status(StepStatus.ERROR)
                _noticeEvent.value = Event(
                    ShareNoticeEvent.ToastNotice(
                        messageResId = R.string.share_receive_error_could_not_find_places,
                    ),
                )
                // TODO: Temporary handling - manual search button to be added later (docs/data-contract.md §8)
            }
        }
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
