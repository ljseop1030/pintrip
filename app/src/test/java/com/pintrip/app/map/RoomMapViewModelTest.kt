package com.pintrip.app.map

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.pintrip.app.data.model.Place
import com.pintrip.app.data.model.PlaceDetail
import com.pintrip.app.data.model.Room
import com.pintrip.app.data.repository.MapCoordinate
import com.pintrip.app.data.repository.PlaceDetailRepository
import com.pintrip.app.data.repository.RoomRepository
import com.pintrip.app.place.PlaceCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private class FakeRoomRepository : RoomRepository(null, null, null) {
    var roomToReturn: Room? = Room(id = "room_1", name = "부산 여행", inviteCode = "ABC123", region = "부산")

    override suspend fun getRoom(roomId: String, source: com.google.firebase.firestore.Source): Room? {
        return roomToReturn
    }
}

private class FakePlaceDetailRepository : PlaceDetailRepository() {
    var detailsToReturn: List<PlaceDetail> = emptyList()
    var shouldFail = false

    override suspend fun getPlaceDetails(roomId: String, roomRegion: String): List<PlaceDetail> {
        if (shouldFail) throw RuntimeException("Network error")
        return detailsToReturn
    }

    override suspend fun searchRegionCenter(region: String): MapCoordinate? {
        return MapCoordinate(35.1796, 129.0756)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RoomMapViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var roomRepository: FakeRoomRepository
    private lateinit var placeDetailRepository: FakePlaceDetailRepository
    private lateinit var viewModel: RoomMapViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        roomRepository = FakeRoomRepository()
        placeDetailRepository = FakePlaceDetailRepository()
        viewModel = RoomMapViewModel(roomRepository, placeDetailRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun selectCategory_duringLoading_appliesCategoryAfterLoadCompletes() = runTest {
        val place1 = PlaceDetail(
            place = Place(kakaoPlaceId = "p1", searchKeyword = "식당"),
            status = PlaceDetail.STATUS_FOUND,
            name = "맛있는 식당",
            lat = 35.1,
            lng = 129.1,
            category = PlaceCategory.DINING.code,
        )
        val place2 = PlaceDetail(
            place = Place(kakaoPlaceId = "p2", searchKeyword = "카페"),
            status = PlaceDetail.STATUS_FOUND,
            name = "예쁜 카페",
            lat = 35.2,
            lng = 129.2,
            category = PlaceCategory.CAFE.code,
        )
        placeDetailRepository.detailsToReturn = listOf(place1, place2)

        // 1. Trigger load
        viewModel.loadRoomMap("room_1")

        // While in loading state, select DINING category
        viewModel.selectCategory(PlaceCategory.DINING)

        // Advance dispatcher to complete loading coroutine
        testDispatcher.scheduler.advanceUntilIdle()

        // 2. Verify state after load completes
        val state = viewModel.uiState.value
        assertTrue(state is RoomMapUiState.Success)
        val success = state as RoomMapUiState.Success

        assertEquals(PlaceCategory.DINING, success.selectedCategory)
        assertEquals(1, success.placeDetails.size)
        assertEquals(place1, success.placeDetails[0])
        assertEquals(2, success.totalPlaceCount)
    }

    @Test
    fun selectCategory_afterLoading_filtersPlacesCorrectly() = runTest {
        val place1 = PlaceDetail(
            place = Place(kakaoPlaceId = "p1", searchKeyword = "식당"),
            status = PlaceDetail.STATUS_FOUND,
            name = "맛있는 식당",
            lat = 35.1,
            lng = 129.1,
            category = PlaceCategory.DINING.code,
        )
        val place2 = PlaceDetail(
            place = Place(kakaoPlaceId = "p2", searchKeyword = "카페"),
            status = PlaceDetail.STATUS_FOUND,
            name = "예쁜 카페",
            lat = 35.2,
            lng = 129.2,
            category = PlaceCategory.CAFE.code,
        )
        placeDetailRepository.detailsToReturn = listOf(place1, place2)

        viewModel.loadRoomMap("room_1")
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify initial state has ALL (2 places)
        val initialState = viewModel.uiState.value as RoomMapUiState.Success
        assertEquals(2, initialState.placeDetails.size)

        // Select CAFE
        viewModel.selectCategory(PlaceCategory.CAFE)
        val updatedState = viewModel.uiState.value as RoomMapUiState.Success

        assertEquals(PlaceCategory.CAFE, updatedState.selectedCategory)
        assertEquals(1, updatedState.placeDetails.size)
        assertEquals(place2, updatedState.placeDetails[0])
        assertEquals(2, updatedState.totalPlaceCount)
    }

    @Test
    fun loadRoomMap_whenFailed_canRetry() = runTest {
        placeDetailRepository.shouldFail = true

        viewModel.loadRoomMap("room_1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is RoomMapUiState.Error)

        // Now fix error and retry
        placeDetailRepository.shouldFail = false
        placeDetailRepository.detailsToReturn = listOf(
            PlaceDetail(
                place = Place(kakaoPlaceId = "p1", searchKeyword = "식당"),
                status = PlaceDetail.STATUS_FOUND,
                name = "맛있는 식당",
                lat = 35.1,
                lng = 129.1,
                category = PlaceCategory.DINING.code,
            )
        )

        viewModel.retry("room_1")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is RoomMapUiState.Success)
        val success = state as RoomMapUiState.Success
        assertEquals(1, success.placeDetails.size)
    }
}
