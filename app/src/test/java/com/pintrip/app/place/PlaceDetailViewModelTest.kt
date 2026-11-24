package com.pintrip.app.place

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.pintrip.app.R
import com.pintrip.app.data.model.Member
import com.pintrip.app.data.model.Place
import com.pintrip.app.data.model.PlaceDetail
import com.pintrip.app.data.model.Room
import com.pintrip.app.data.repository.PlaceDetailRepository
import com.pintrip.app.data.repository.PlaceRepository
import com.pintrip.app.data.repository.RoomRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private class FakeRoomRepository : RoomRepository(null, null, null) {
    var roomToReturn: Room? = Room(
        id = "room_1",
        name = "부산 여행",
        region = "부산",
        members = mapOf(
            "user_me" to Member(nickname = "내닉네임", avatarColor = "#FF0000"),
            "user_other" to Member(nickname = "다른사람", avatarColor = "#00FF00")
        )
    )

    override suspend fun getRoom(roomId: String, source: com.google.firebase.firestore.Source): Room? {
        return roomToReturn
    }
}

private class FakePlaceDetailRepository : PlaceDetailRepository() {
    var placeDetailToReturn: PlaceDetail? = PlaceDetail(
        place = Place(
            kakaoPlaceId = "place_1",
            searchKeyword = "해운대",
            addedBy = "user_me",
            likedBy = emptyList(),
            likeCount = 0
        ),
        status = PlaceDetail.STATUS_FOUND,
        name = "해운대 해수욕장"
    )
    var getPlaceDetailCallCount = 0

    override suspend fun getPlaceDetail(roomId: String, roomRegion: String, kakaoPlaceId: String): PlaceDetail? {
        getPlaceDetailCallCount++
        return placeDetailToReturn
    }
}

private class FakePlaceRepository : PlaceRepository(null, null) {
    val placeFlow = MutableSharedFlow<Place?>(replay = 1)
    var toggleLikeCallCount = 0
    var toggleLikeShouldFail = false
    var toggleLikeDelayMs = 0L

    override fun observePlace(roomId: String, kakaoPlaceId: String): Flow<Place?> {
        return placeFlow
    }

    override suspend fun toggleLike(roomId: String, kakaoPlaceId: String): Boolean {
        toggleLikeCallCount++
        if (toggleLikeDelayMs > 0) {
            delay(toggleLikeDelayMs)
        }
        if (toggleLikeShouldFail) {
            throw RuntimeException("Toggle like failed")
        }
        return true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlaceDetailViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var roomRepository: FakeRoomRepository
    private lateinit var placeDetailRepository: FakePlaceDetailRepository
    private lateinit var placeRepository: FakePlaceRepository
    private lateinit var viewModel: PlaceDetailViewModel
    private val myUid = "user_me"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        roomRepository = FakeRoomRepository()
        placeDetailRepository = FakePlaceDetailRepository()
        placeRepository = FakePlaceRepository()
        viewModel = PlaceDetailViewModel(
            roomRepository = roomRepository,
            placeDetailRepository = placeDetailRepository,
            placeRepository = placeRepository,
            currentUidProvider = { myUid }
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun whenListenerEmitsPlaceWithMyUidInLikedBy_updatesIsLikedByMeAndLikedUsers() = runTest {
        val initialPlace = Place(kakaoPlaceId = "place_1", likedBy = emptyList(), likeCount = 0, addedBy = "user_me")
        placeDetailRepository.placeDetailToReturn = PlaceDetail(place = initialPlace, status = PlaceDetail.STATUS_FOUND, name = "해운대")

        viewModel.loadPlaceDetail("room_1", "place_1")
        testDispatcher.scheduler.advanceUntilIdle()

        val initialState = viewModel.uiState.value as PlaceDetailUiState.Success
        assertEquals(false, initialState.isLikedByMe)
        assertEquals(0, initialState.likedUsers.size)

        // 리스너로 likedBy에 내 uid가 추가된 Place 전달
        val updatedPlace = initialPlace.copy(likedBy = listOf("user_me"), likeCount = 1)
        placeRepository.placeFlow.emit(updatedPlace)
        testDispatcher.scheduler.advanceUntilIdle()

        val updatedState = viewModel.uiState.value as PlaceDetailUiState.Success
        assertEquals(true, updatedState.isLikedByMe)
        assertEquals(1, updatedState.placeDetail.place.likeCount)
        assertEquals(1, updatedState.likedUsers.size)
        assertEquals("user_me", updatedState.likedUsers[0].uid)
        assertEquals("내닉네임", updatedState.likedUsers[0].nickname)
    }

    @Test
    fun whenListenerUpdates_kakaoReFetchIsNotCalledAgain() = runTest {
        val initialPlace = Place(kakaoPlaceId = "place_1", likedBy = emptyList(), likeCount = 0, addedBy = "user_me")
        placeDetailRepository.placeDetailToReturn = PlaceDetail(place = initialPlace, status = PlaceDetail.STATUS_FOUND, name = "해운대")

        viewModel.loadPlaceDetail("room_1", "place_1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, placeDetailRepository.getPlaceDetailCallCount)

        // 리스너 갱신 2회 발생
        placeRepository.placeFlow.emit(initialPlace.copy(likedBy = listOf("user_other"), likeCount = 1))
        testDispatcher.scheduler.advanceUntilIdle()

        placeRepository.placeFlow.emit(initialPlace.copy(likedBy = listOf("user_other", "user_me"), likeCount = 2))
        testDispatcher.scheduler.advanceUntilIdle()

        // 카카오 재조회(getPlaceDetail)는 여전히 1회만 호출되어야 함
        assertEquals(1, placeDetailRepository.getPlaceDetailCallCount)
    }

    @Test
    fun whenToggleLikeCalledMultipleTimesWhileInProgress_callsRepositoryToggleLikeOnlyOnce() = runTest {
        val initialPlace = Place(kakaoPlaceId = "place_1", likedBy = emptyList(), likeCount = 0, addedBy = "user_me")
        placeDetailRepository.placeDetailToReturn = PlaceDetail(place = initialPlace, status = PlaceDetail.STATUS_FOUND, name = "해운대")

        viewModel.loadPlaceDetail("room_1", "place_1")
        testDispatcher.scheduler.advanceUntilIdle()

        // toggleLike에 딜레이 설정하여 진행 중 상태 유지
        placeRepository.toggleLikeDelayMs = 1000L

        // 첫 번째 호출
        viewModel.toggleLike()

        val state1 = viewModel.uiState.value as PlaceDetailUiState.Success
        assertEquals(true, state1.isLikeInProgress)

        // 진행 중일 때 두 번째 호출
        viewModel.toggleLike()

        // 코루틴 완료
        testDispatcher.scheduler.advanceUntilIdle()

        // repository.toggleLike는 1번만 호출되어야 함
        assertEquals(1, placeRepository.toggleLikeCallCount)

        val finalState = viewModel.uiState.value as PlaceDetailUiState.Success
        assertEquals(false, finalState.isLikeInProgress)
    }

    @Test
    fun whenToggleLikeFails_emitsToastEventAndResetsIsLikeInProgress() = runTest {
        val initialPlace = Place(kakaoPlaceId = "place_1", likedBy = emptyList(), likeCount = 0, addedBy = "user_me")
        placeDetailRepository.placeDetailToReturn = PlaceDetail(place = initialPlace, status = PlaceDetail.STATUS_FOUND, name = "해운대")

        viewModel.loadPlaceDetail("room_1", "place_1")
        testDispatcher.scheduler.advanceUntilIdle()

        placeRepository.toggleLikeShouldFail = true

        viewModel.toggleLike()
        testDispatcher.scheduler.advanceUntilIdle()

        // 토스트 Event 검증
        val toastEvent = viewModel.toastEvent.value
        assertNotNull(toastEvent)
        assertEquals(R.string.place_detail_like_failed, toastEvent?.getContentIfNotHandled())

        // isLikeInProgress 해제 검증
        val state = viewModel.uiState.value as PlaceDetailUiState.Success
        assertEquals(false, state.isLikeInProgress)
    }

    @Test
    fun whenListenerEmitsNull_stateBecomesNotFoundInFirestore() = runTest {
        val initialPlace = Place(kakaoPlaceId = "place_1", likedBy = emptyList(), likeCount = 0, addedBy = "user_me")
        placeDetailRepository.placeDetailToReturn = PlaceDetail(place = initialPlace, status = PlaceDetail.STATUS_FOUND, name = "해운대")

        viewModel.loadPlaceDetail("room_1", "place_1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is PlaceDetailUiState.Success)

        // 리스너가 null(삭제됨)을 보냄
        placeRepository.placeFlow.emit(null)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is PlaceDetailUiState.NotFoundInFirestore)
    }
}
