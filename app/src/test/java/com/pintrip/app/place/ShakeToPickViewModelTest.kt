package com.pintrip.app.place

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.pintrip.app.R
import com.pintrip.app.data.model.Place
import com.pintrip.app.data.model.PlaceDetail
import com.pintrip.app.data.model.Room
import com.pintrip.app.data.repository.PlaceDetailRepository
import com.pintrip.app.data.repository.RoomRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Random

open class FakeShakeRoomRepository : RoomRepository(null, null, null) {
    var roomToReturn: Room? = Room(id = "room_1", name = "부산 여행", region = "부산")
    var exceptionToThrow: Exception? = null
    var getRoomCallCount = 0

    override suspend fun getRoom(roomId: String, source: Source): Room? {
        getRoomCallCount++
        exceptionToThrow?.let { throw it }
        return roomToReturn
    }
}

open class FakeShakePlaceDetailRepository : PlaceDetailRepository() {
    var detailsToReturn: List<PlaceDetail> = emptyList()
    var exceptionToThrow: Exception? = null
    var getPlaceDetailsCallCount = 0
    var delayMs: Long = 0L

    override suspend fun getPlaceDetails(roomId: String, roomRegion: String): List<PlaceDetail> {
        getPlaceDetailsCallCount++
        if (delayMs > 0) {
            delay(delayMs)
        }
        exceptionToThrow?.let { throw it }
        return detailsToReturn
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShakeToPickViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var fakeRoomRepo: FakeShakeRoomRepository
    private lateinit var fakeDetailRepo: FakeShakePlaceDetailRepository

    private val foundDining = PlaceDetail(
        place = Place(kakaoPlaceId = "p1", searchKeyword = "이재모피자"),
        status = PlaceDetail.STATUS_FOUND,
        name = "이재모피자 본점",
        category = "dining",
    )
    private val foundCafe = PlaceDetail(
        place = Place(kakaoPlaceId = "p2", searchKeyword = "모모스커피"),
        status = PlaceDetail.STATUS_FOUND,
        name = "모모스커피 본점",
        category = "cafe",
    )
    private val notFoundPlace = PlaceDetail(
        place = Place(kakaoPlaceId = "p3", searchKeyword = "알수없는곳"),
        status = PlaceDetail.STATUS_NOT_FOUND,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRoomRepo = FakeShakeRoomRepository()
        fakeDetailRepo = FakeShakePlaceDetailRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadPlaces_blankRoomId_setsErrorState() {
        val viewModel = ShakeToPickViewModel(fakeRoomRepo, fakeDetailRepo)

        viewModel.loadPlaces("")

        val state = viewModel.uiState.value
        assertTrue(state is ShakeToPickUiState.Error)
        assertEquals(R.string.place_list_error_no_room_id, (state as ShakeToPickUiState.Error).messageResId)
    }

    @Test
    fun loadPlaces_emptySavedPlaces_setsEmptyStateWithNoSavedPlacesReason() {
        fakeDetailRepo.detailsToReturn = emptyList()
        val viewModel = ShakeToPickViewModel(fakeRoomRepo, fakeDetailRepo)

        viewModel.loadPlaces("room_1")

        val state = viewModel.uiState.value
        assertTrue(state is ShakeToPickUiState.Empty)
        val emptyState = state as ShakeToPickUiState.Empty
        assertEquals(EmptyReason.NO_SAVED_PLACES, emptyState.reason)
        assertEquals(0, emptyState.totalCount)
        assertEquals(0, emptyState.availableCount)
    }

    @Test
    fun loadPlaces_allNotFoundPlaces_setsEmptyStateWithNoAvailablePlacesReason() {
        fakeDetailRepo.detailsToReturn = listOf(notFoundPlace)
        val viewModel = ShakeToPickViewModel(fakeRoomRepo, fakeDetailRepo)

        viewModel.loadPlaces("room_1")

        val state = viewModel.uiState.value
        assertTrue(state is ShakeToPickUiState.Empty)
        val emptyState = state as ShakeToPickUiState.Empty
        assertEquals(EmptyReason.NO_AVAILABLE_PLACES, emptyState.reason)
        assertEquals(1, emptyState.totalCount)
        assertEquals(0, emptyState.availableCount)
    }

    @Test
    fun loadPlaces_successWithFoundPlaces_setsReadyState() {
        fakeDetailRepo.detailsToReturn = listOf(foundDining, foundCafe, notFoundPlace)
        val viewModel = ShakeToPickViewModel(fakeRoomRepo, fakeDetailRepo)

        viewModel.loadPlaces("room_1")

        val state = viewModel.uiState.value
        assertTrue(state is ShakeToPickUiState.Ready)
        val readyState = state as ShakeToPickUiState.Ready
        assertEquals(PlaceCategory.ALL, readyState.category)
        assertEquals(3, readyState.totalCount)
        assertEquals(2, readyState.availableCount)
        assertEquals(2, readyState.filteredCount)
    }

    @Test
    fun selectCategory_noFilterMatches_setsEmptyStateWithNoFilterMatchesReason() {
        fakeDetailRepo.detailsToReturn = listOf(foundDining, notFoundPlace)
        val viewModel = ShakeToPickViewModel(fakeRoomRepo, fakeDetailRepo)

        viewModel.loadPlaces("room_1")
        assertEquals(1, fakeDetailRepo.getPlaceDetailsCallCount)

        // CAFE 선택 (dining만 있으므로 필터 결과 0개)
        viewModel.selectCategory(PlaceCategory.CAFE)

        // 네트워크/Repository 재호출 없음 확인
        assertEquals(1, fakeDetailRepo.getPlaceDetailsCallCount)

        val state = viewModel.uiState.value
        assertTrue(state is ShakeToPickUiState.Empty)
        val emptyState = state as ShakeToPickUiState.Empty
        assertEquals(EmptyReason.NO_FILTER_MATCHES, emptyState.reason)
        assertEquals(PlaceCategory.CAFE, emptyState.category)
        assertEquals(2, emptyState.totalCount)
        assertEquals(1, emptyState.availableCount)
    }

    @Test
    fun selectCategory_filterMatches_updatesFilteredCountAndResetsPreviousResult() {
        fakeDetailRepo.detailsToReturn = listOf(foundDining, foundCafe)
        val fixedRandom = object : Random() {
            override fun nextInt(bound: Int): Int = 0
        }
        val viewModel = ShakeToPickViewModel(fakeRoomRepo, fakeDetailRepo, fixedRandom)

        viewModel.loadPlaces("room_1")
        viewModel.pickRandomPlace()

        assertTrue(viewModel.uiState.value is ShakeToPickUiState.Result)

        // 카테고리를 DINING으로 변경 -> 이전 Result 결과가 취소되고 새 카테고리 Ready 상태로 정리됨
        viewModel.selectCategory(PlaceCategory.DINING)

        val state = viewModel.uiState.value
        assertTrue(state is ShakeToPickUiState.Ready)
        val readyState = state as ShakeToPickUiState.Ready
        assertEquals(PlaceCategory.DINING, readyState.category)
        assertEquals(2, readyState.totalCount)
        assertEquals(2, readyState.availableCount)
        assertEquals(1, readyState.filteredCount)
    }

    @Test
    fun pickRandomPlace_picksItemUsingRandomIndexAndFiresEvent() {
        fakeDetailRepo.detailsToReturn = listOf(foundDining, foundCafe)

        // 항상 인덱스 1 (foundCafe)을 반환하는 고정 Random
        val fixedRandom = object : Random() {
            override fun nextInt(bound: Int): Int = 1
        }
        val viewModel = ShakeToPickViewModel(fakeRoomRepo, fakeDetailRepo, fixedRandom)

        viewModel.loadPlaces("room_1")
        viewModel.pickRandomPlace()

        val state = viewModel.uiState.value
        assertTrue(state is ShakeToPickUiState.Result)
        val resultState = state as ShakeToPickUiState.Result
        assertEquals("모모스커피 본점", resultState.selectedPlace.name)
        assertEquals(PlaceCategory.ALL, resultState.category)
        assertEquals(2, resultState.filteredCount)

        assertNotNull(viewModel.shakeEvent.value?.getContentIfNotHandled())
    }

    @Test
    fun pickRandomPlace_allowsRepeatedIndependentPicks() {
        fakeDetailRepo.detailsToReturn = listOf(foundDining)
        val viewModel = ShakeToPickViewModel(fakeRoomRepo, fakeDetailRepo)

        viewModel.loadPlaces("room_1")

        viewModel.pickRandomPlace()
        assertTrue(viewModel.uiState.value is ShakeToPickUiState.Result)

        // 두 번째 연속 추첨 (동일 장소 재선택 허용)
        viewModel.pickRandomPlace()
        assertTrue(viewModel.uiState.value is ShakeToPickUiState.Result)
        val resultState = viewModel.uiState.value as ShakeToPickUiState.Result
        assertEquals("이재모피자 본점", resultState.selectedPlace.name)
    }

    @Test
    fun loadPlaces_duplicateCallWithSameRoomId_ignoredUnlessForceRefresh() {
        fakeDetailRepo.detailsToReturn = listOf(foundDining)
        val viewModel = ShakeToPickViewModel(fakeRoomRepo, fakeDetailRepo)

        viewModel.loadPlaces("room_1")
        assertEquals(1, fakeDetailRepo.getPlaceDetailsCallCount)

        // 동일 roomId로 재호출 (forceRefresh=false) -> 무시됨
        viewModel.loadPlaces("room_1", forceRefresh = false)
        assertEquals(1, fakeDetailRepo.getPlaceDetailsCallCount)

        // forceRefresh=true -> 다시 재조회 실행됨
        viewModel.retry()
        assertEquals(2, fakeDetailRepo.getPlaceDetailsCallCount)
    }

    @Test
    fun loadPlaces_duplicateLoadWhileLoading_doesNotTriggerAdditionalFetch() =
        kotlinx.coroutines.test.runTest {
            fakeDetailRepo.delayMs = 500L
            fakeDetailRepo.detailsToReturn = listOf(foundDining)
            val viewModel = ShakeToPickViewModel(fakeRoomRepo, fakeDetailRepo)

            viewModel.loadPlaces("room_1")
            assertTrue(viewModel.uiState.value is ShakeToPickUiState.Loading)
            assertEquals(1, fakeDetailRepo.getPlaceDetailsCallCount)

            viewModel.loadPlaces("room_1", forceRefresh = false)
            assertTrue(viewModel.uiState.value is ShakeToPickUiState.Loading)
            assertEquals(1, fakeDetailRepo.getPlaceDetailsCallCount)

            testScheduler.advanceUntilIdle()
            assertTrue(viewModel.uiState.value is ShakeToPickUiState.Ready)
            assertEquals(1, fakeDetailRepo.getPlaceDetailsCallCount)
        }

    @Test
    fun selectCategory_followedByPickRandomPlace_selectsOnlyFilteredFoundItemsWithoutReFetch() {
        fakeDetailRepo.detailsToReturn = listOf(foundDining, foundCafe, notFoundPlace)
        val viewModel = ShakeToPickViewModel(fakeRoomRepo, fakeDetailRepo)

        viewModel.loadPlaces("room_1")
        viewModel.selectCategory(PlaceCategory.CAFE)
        viewModel.pickRandomPlace()

        val state = viewModel.uiState.value
        assertTrue(state is ShakeToPickUiState.Result)
        val result = state as ShakeToPickUiState.Result
        assertEquals(PlaceCategory.CAFE, result.category)
        assertEquals(PlaceDetail.STATUS_FOUND, result.selectedPlace.status)
        assertEquals(foundCafe.place.kakaoPlaceId, result.selectedPlace.place.kakaoPlaceId)
        assertEquals(1, result.filteredCount)
        assertEquals(1, fakeDetailRepo.getPlaceDetailsCallCount)
    }

    @Test
    fun loadPlaces_whenLoadingRoomA_thenEmptyRoomId_cancelsRoomAAndHoldsErrorState() = kotlinx.coroutines.test.runTest {
        // Room A 요청 시 500ms 지연 설정
        fakeDetailRepo.delayMs = 500L
        fakeDetailRepo.detailsToReturn = listOf(foundDining)
        val viewModel = ShakeToPickViewModel(fakeRoomRepo, fakeDetailRepo)

        // 1. Room A 조회 시작 (Loading 상태)
        viewModel.loadPlaces("room_1")
        assertTrue(viewModel.uiState.value is ShakeToPickUiState.Loading)

        // 2. Room A 로딩 중 빈 roomId로 loadPlaces("") 호출 -> Error 상태로 전환
        viewModel.loadPlaces("")
        val stateAfterBlank = viewModel.uiState.value
        assertTrue(stateAfterBlank is ShakeToPickUiState.Error)
        assertEquals(R.string.place_list_error_no_room_id, (stateAfterBlank as ShakeToPickUiState.Error).messageResId)

        // 3. 지연 시간(500ms) 경과 후 Room A 조회가 뒤늦게 완료되어도 Error 상태가 유지되어야 함
        testScheduler.advanceUntilIdle()
        val stateAfterDelay = viewModel.uiState.value
        assertTrue("이전 A 조회가 완료되어도 빈 roomId의 Error 상태가 유지되어야 함", stateAfterDelay is ShakeToPickUiState.Error)
        assertEquals(R.string.place_list_error_no_room_id, (stateAfterDelay as ShakeToPickUiState.Error).messageResId)

        // 4. retry() 및 pickRandomPlace()가 room_1 데이터로 동작하지 않음
        viewModel.retry()
        assertTrue("currentRoomId가 null이므로 retry() 시 재조회하지 않아야 함", viewModel.uiState.value is ShakeToPickUiState.Error)

        viewModel.pickRandomPlace()
        assertTrue("Error 상태에서는 추첨이 진행되지 않아야 함", viewModel.uiState.value is ShakeToPickUiState.Error)
    }

    @Test
    fun loadPlaces_roomAInterleavedWithRoomB_keepsRoomBResultEvenIfACompletesLater() = kotlinx.coroutines.test.runTest {
        val roomA = Room(id = "room_A", name = "서울", region = "서울")
        val roomB = Room(id = "room_B", name = "부산", region = "부산")

        val customRoomRepo = object : FakeShakeRoomRepository() {
            override suspend fun getRoom(roomId: String, source: Source): Room? {
                return if (roomId == "room_A") roomA else roomB
            }
        }
        val customDetailRepo = object : FakeShakePlaceDetailRepository() {
            override suspend fun getPlaceDetails(roomId: String, roomRegion: String): List<PlaceDetail> {
                getPlaceDetailsCallCount++
                return if (roomId == "room_A") {
                    // NonCancellable을 사용하여 취소 후에도 늦게 완료되는 상황을 명시적으로 만듦
                    withContext(NonCancellable) {
                        delay(500L)
                        listOf(foundDining)
                    }
                } else {
                    delay(100L)
                    listOf(foundCafe)
                }
            }
        }
        val viewModel = ShakeToPickViewModel(customRoomRepo, customDetailRepo)

        // 1. room_A 조회 시작 (NonCancellable 500ms 소요)
        viewModel.loadPlaces("room_A")

        // 2. room_A 로딩 중 room_B 조회 시작 (100ms 소요, room_A 취소 요청)
        viewModel.loadPlaces("room_B")

        // 3. 시간 전진 (100ms 후 room_B 완료, 500ms 후 취소된 room_A가 NonCancellable로 늦게 완료됨)
        testScheduler.advanceUntilIdle()

        // 4. 최종 UI 상태는 room_B의 결과(foundCafe)여야 함
        val state = viewModel.uiState.value
        assertTrue("최신 요청인 room_B의 결과가 유지되어야 함", state is ShakeToPickUiState.Ready)

        viewModel.pickRandomPlace()
        val resultState = viewModel.uiState.value as ShakeToPickUiState.Result
        assertEquals("room_B의 장소가 선택되어야 함", "모모스커피 본점", resultState.selectedPlace.name)
    }

    @Test
    fun loadPlaces_sameRoomIdForceRefresh_firstRequestCompletesNonCancellableLater_keepsSecondResult() = kotlinx.coroutines.test.runTest {
        var callIndex = 0
        val customDetailRepo = object : FakeShakePlaceDetailRepository() {
            override suspend fun getPlaceDetails(roomId: String, roomRegion: String): List<PlaceDetail> {
                callIndex++
                val currentCall = callIndex
                return if (currentCall == 1) {
                    // 첫 번째 요청: 취소되더라도 NonCancellable로 500ms 후 늦게 완료되어 foundDining 반환
                    withContext(NonCancellable) {
                        delay(500L)
                        listOf(foundDining)
                    }
                } else {
                    // 두 번째 요청: 100ms 후 먼저 완료되어 foundCafe 반환
                    delay(100L)
                    listOf(foundCafe)
                }
            }
        }
        val viewModel = ShakeToPickViewModel(fakeRoomRepo, customDetailRepo)

        // 1. 동일한 roomId로 첫 번째 forceRefresh 조회 실행 (500ms NonCancellable)
        viewModel.loadPlaces("room_1", forceRefresh = true)

        // 2. 동일한 roomId로 두 번째 forceRefresh 조회 실행 (100ms) -> 첫 번째 요청 취소
        viewModel.loadPlaces("room_1", forceRefresh = true)

        // 3. 시간 전진 (두 번째 요청이 100ms에 먼저 완료되고, 첫 번째 요청이 500ms에 늦게 완료됨)
        testScheduler.advanceUntilIdle()

        // 4. 요청 세대 번호(currentRequestId) 가드로 인해 첫 번째 늦은 응답이 두 번째 결과를 덮어쓰지 않고 foundCafe가 유지되어야 함
        val state = viewModel.uiState.value
        assertTrue("동일 roomId 재조회 시 두 번째 최신 요청의 결과가 유지되어야 함", state is ShakeToPickUiState.Ready)

        viewModel.pickRandomPlace()
        val resultState = viewModel.uiState.value as ShakeToPickUiState.Result
        assertEquals("두 번째 요청 결과인 모모스커피 본점이 선택되어야 함", "모모스커피 본점", resultState.selectedPlace.name)
    }

    @Test
    fun retry_afterGeneralError_retriesAndRecoversToReadyState() {
        fakeDetailRepo.exceptionToThrow = RuntimeException("General Error")
        val viewModel = ShakeToPickViewModel(fakeRoomRepo, fakeDetailRepo)

        viewModel.loadPlaces("room_1")
        assertTrue(viewModel.uiState.value is ShakeToPickUiState.Error)
        assertEquals(1, fakeDetailRepo.getPlaceDetailsCallCount)

        // 오류 해결 후 retry()
        fakeDetailRepo.exceptionToThrow = null
        fakeDetailRepo.detailsToReturn = listOf(foundDining)
        viewModel.retry()

        assertEquals(2, fakeDetailRepo.getPlaceDetailsCallCount)
        assertTrue(viewModel.uiState.value is ShakeToPickUiState.Ready)
    }

    @Test
    fun loadPlaces_cancellationException_isNotCapturedAsUiError() {
        fakeDetailRepo.exceptionToThrow = CancellationException("Job cancelled")
        val viewModel = ShakeToPickViewModel(fakeRoomRepo, fakeDetailRepo)

        viewModel.loadPlaces("room_1")

        val state = viewModel.uiState.value
        assertTrue("CancellationException 발생 시 UiState.Error로 변환되지 않고 재던져져야 함", state is ShakeToPickUiState.Loading)
    }

    @Test
    fun loadPlaces_firestorePermissionDenied_setsPermissionDeniedErrorMessage() {
        fakeDetailRepo.exceptionToThrow = FirebaseFirestoreException(
            "Denied",
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
        )
        val viewModel = ShakeToPickViewModel(fakeRoomRepo, fakeDetailRepo)

        viewModel.loadPlaces("room_1")

        val state = viewModel.uiState.value
        assertTrue(state is ShakeToPickUiState.Error)
        assertEquals(R.string.error_room_permission_denied, (state as ShakeToPickUiState.Error).messageResId)
    }

    @Test
    fun loadPlaces_nullRoom_setsNoRoomIdErrorAndSkipsDetailsFetch() {
        fakeRoomRepo.roomToReturn = null
        val viewModel = ShakeToPickViewModel(fakeRoomRepo, fakeDetailRepo)

        viewModel.loadPlaces("room_1")

        val state = viewModel.uiState.value
        assertTrue(state is ShakeToPickUiState.Error)
        assertEquals(R.string.place_list_error_no_room_id, (state as ShakeToPickUiState.Error).messageResId)
        assertEquals("방 정보가 없으면 장소 상세 조회가 실행되지 않아야 함", 0, fakeDetailRepo.getPlaceDetailsCallCount)

        viewModel.pickRandomPlace()
        assertTrue("Error 상태에서는 추첨이 진행되지 않고 Error가 유지되어야 함", viewModel.uiState.value is ShakeToPickUiState.Error)
        assertNull("Error 상태에서는 추첨 이벤트가 발생하지 않아야 함", viewModel.shakeEvent.value?.getContentIfNotHandled())
    }
}
