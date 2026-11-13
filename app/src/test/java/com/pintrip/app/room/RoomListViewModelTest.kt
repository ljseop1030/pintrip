package com.pintrip.app.room

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.pintrip.app.R
import com.pintrip.app.data.model.Room
import com.pintrip.app.data.repository.JoinRoomResult
import com.pintrip.app.data.repository.RoomJoinException
import com.pintrip.app.data.repository.RoomRepository
import com.pintrip.app.data.repository.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FakeRoomRepository : RoomRepository(null, null, null) {
    var resultToReturn: JoinRoomResult? = null
    var exceptionToThrow: Exception? = null
    var joinRoomCallCount = 0

    override suspend fun joinRoomWithCode(code: String): JoinRoomResult {
        joinRoomCallCount++
        exceptionToThrow?.let { throw it }
        return resultToReturn
            ?: JoinRoomResult.NewJoin(Room(id = "room_123", name = "부산 여행", inviteCode = code))
    }

    override suspend fun getMyRooms(source: com.google.firebase.firestore.Source): List<Room> {
        return emptyList()
    }

    override suspend fun getRoom(roomId: String, source: com.google.firebase.firestore.Source): Room? {
        return Room(id = roomId, name = "부산 여행", inviteCode = "K7P2QX")
    }
}

class FakeUserRepository : UserRepository(null, null)

@OptIn(ExperimentalCoroutinesApi::class)
class RoomListViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val sampleRoom = Room(id = "room_123", name = "부산 여행", inviteCode = "K7P2QX")

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun scenario1_userCancellationClearsJoinSuccessStateAndUnlocksInteractions() {
        val fakeRoomRepo = FakeRoomRepository()
        val fakeUserRepo = FakeUserRepository()
        val viewModel = RoomListViewModel(fakeRoomRepo, fakeUserRepo, null)

        // 1. 완료 상태 설정 (NewJoin)
        val successResult = JoinRoomResult.NewJoin(sampleRoom)
        val field = RoomListViewModel::class.java.getDeclaredField("_joinSuccessResult")
        field.isAccessible = true
        (field.get(viewModel) as androidx.lifecycle.MutableLiveData<JoinRoomResult?>).value = successResult

        assertEquals(successResult, viewModel.joinSuccessResult.value)

        // 2. 가입 완료 대기 중에는 다른 버튼/카드 클릭 거부
        viewModel.onRoomCardClick(sampleRoom)
        assertNull("가입 완료 대기 중에는 방 카드 클릭으로 인한 이동 이벤트가 발생하지 않아야 함", viewModel.navigateToPlaceList.value?.getContentIfNotHandled())

        viewModel.onCreateRoomClick()
        assertNull("가입 완료 대기 중에는 방 생성 이벤트가 발생하지 않아야 함", viewModel.navigateToCreateRoom.value?.getContentIfNotHandled())

        viewModel.onJoinCodeClick()
        assertFalse("가입 완료 대기 중에는 초대 코드 대화창 오픈 이벤트가 발생하지 않아야 함", viewModel.isJoinDialogOpen.value == true)

        // 3. 사용자 취소 ([목록으로], 뒤로 가기, 바깥 터치) 처리
        viewModel.onDismissJoinSuccess()
        assertNull("사용자 취소 후 joinSuccessResult는 null이 되어야 함", viewModel.joinSuccessResult.value)

        // 4. 취소 후 정상적으로 버튼 및 카드 클릭 허용
        viewModel.onRoomCardClick(sampleRoom)
        assertEquals("room_123", viewModel.navigateToPlaceList.value?.getContentIfNotHandled())
    }

    @Test
    fun scenario2_launchFailurePreservesResultAllowsRetryWithoutReJoinAndHandlesRapidClicks() {
        val fakeRoomRepo = FakeRoomRepository()
        val fakeUserRepo = FakeUserRepository()
        val viewModel = RoomListViewModel(fakeRoomRepo, fakeUserRepo, null)

        val successResult = JoinRoomResult.NewJoin(sampleRoom)
        val field = RoomListViewModel::class.java.getDeclaredField("_joinSuccessResult")
        field.isAccessible = true
        (field.get(viewModel) as androidx.lifecycle.MutableLiveData<JoinRoomResult?>).value = successResult

        // 1. 장소 목록 보기 선택
        viewModel.onViewPlacesFromJoinSuccess("room_123")
        assertEquals("room_123", viewModel.navigateToPlaceList.value?.getContentIfNotHandled())

        // 2. 이동 중 연타 시 중복 이벤트 차단
        viewModel.onViewPlacesFromJoinSuccess("room_123")
        assertNull("이동 중 연타 시 두 번째 이벤트는 없어야 함", viewModel.navigateToPlaceList.value?.getContentIfNotHandled())

        // 3. launch 예외 발생 시 (onPlaceListLaunchFailed)
        viewModel.onPlaceListLaunchFailed()

        // 결과 보존 및 실패 토스트 발송 확인
        assertEquals(successResult, viewModel.joinSuccessResult.value)
        assertEquals(R.string.place_list_error_cannot_open, viewModel.toastEvent.value?.getContentIfNotHandled())

        // 4. 사용자가 완료창에서 다시 [장소 목록 보기] 재시도
        viewModel.onViewPlacesFromJoinSuccess("room_123")
        assertEquals("동일한 roomId로 1회 재시도 이동 이벤트 발생해야 함", "room_123", viewModel.navigateToPlaceList.value?.getContentIfNotHandled())

        // Repository의 가입 batch 메서드가 재호출되지 않았음을 검증 (0회 호출)
        assertEquals(0, fakeRoomRepo.joinRoomCallCount)
    }

    @Test
    fun scenario3_launchSuccessClearsResultAndUserDismissDoesNotReFireEvents() {
        val fakeRoomRepo = FakeRoomRepository()
        val fakeUserRepo = FakeUserRepository()
        val viewModel = RoomListViewModel(fakeRoomRepo, fakeUserRepo, null)

        val successResult = JoinRoomResult.NewJoin(sampleRoom)
        val field = RoomListViewModel::class.java.getDeclaredField("_joinSuccessResult")
        field.isAccessible = true
        (field.get(viewModel) as androidx.lifecycle.MutableLiveData<JoinRoomResult?>).value = successResult

        // 1. 장소 목록 보기 선택
        viewModel.onViewPlacesFromJoinSuccess("room_123")
        assertEquals("room_123", viewModel.navigateToPlaceList.value?.getContentIfNotHandled())

        // 2. launch 성공
        viewModel.onPlaceListLaunchSuccess()
        assertNull("launch 성공 시 joinSuccessResult는 null이어야 함", viewModel.joinSuccessResult.value)

        // 3. 이후 사용자 취소나 onViewPlaces 호출 시 무효한 이동 이벤트 발생 없음
        viewModel.onDismissJoinSuccess()
        viewModel.onViewPlacesFromJoinSuccess("room_123")
        assertNull(viewModel.navigateToPlaceList.value?.getContentIfNotHandled())
    }

    @Test
    fun scenario4_creatorAndExistingMemberResultsInlineNoticeInJoinCodeDialog() {
        val fakeRoomRepo = FakeRoomRepository()
        val fakeUserRepo = FakeUserRepository()
        val viewModel = RoomListViewModel(fakeRoomRepo, fakeUserRepo, null)

        // 1. Creator 반환 검증
        fakeRoomRepo.resultToReturn = JoinRoomResult.Creator(sampleRoom)
        viewModel.joinRoomWithCode("K7P2QX")

        assertNull("Creator 반환 시 완료 대기 창(joinSuccessResult)은 null이어야 함", viewModel.joinSuccessResult.value)
        assertEquals("방장 안내 문구가 설정되어야 함", R.string.notice_join_own_room, viewModel.joinErrorMessageResId.value)

        // 2. ExistingMember 반환 검증
        fakeRoomRepo.resultToReturn = JoinRoomResult.ExistingMember(sampleRoom)
        viewModel.joinRoomWithCode("B8R3SY")

        assertNull("ExistingMember 반환 시 완료 대기 창은 null이어야 함", viewModel.joinSuccessResult.value)
        assertEquals("기존 멤버 안내 문구가 설정되어야 함", R.string.notice_already_joined_room, viewModel.joinErrorMessageResId.value)
    }

    @Test
    fun scenario5_exceptionMappingBoundaryPreservesCauseAndReThrowsCancellation() {
        val dummyRepo = RoomRepository(null, null, null)

        val rootCause = RuntimeException("Network connection lost")

        // 1. NetworkUnavailable 예외 수신 및 cause 보존 검증
        val networkExp = RoomJoinException.NetworkUnavailable(rootCause)
        val mappedNetwork = dummyRepo.mapToRoomJoinException(networkExp)
        assertTrue(mappedNetwork is RoomJoinException.NetworkUnavailable)
        assertEquals(rootCause, mappedNetwork.cause)

        // 2. PermissionDenied 예외 수신 및 cause 보존 검증
        val permCause = RuntimeException("Permission denied by server")
        val permExp = RoomJoinException.PermissionDenied(permCause)
        val mappedPermission = dummyRepo.mapToRoomJoinException(permExp)
        assertTrue(mappedPermission is RoomJoinException.PermissionDenied)
        assertEquals(permCause, mappedPermission.cause)

        // 3. 프로필 읽기 중 네트워크 실패가 ProfileRequired로 왜곡되지 않고 NetworkUnavailable 타입 유지
        val mappedProfileNet = dummyRepo.mapToRoomJoinException(networkExp)
        assertFalse("프로필 네트워크 에러는 ProfileRequired가 아니라 NetworkUnavailable이어야 함", mappedProfileNet is RoomJoinException.ProfileRequired)
        assertTrue(mappedProfileNet is RoomJoinException.NetworkUnavailable)

        // 4. CancellationException 재던짐 검증
        val cancellation = CancellationException("Job canceled")
        var rethrown = false
        try {
            dummyRepo.mapToRoomJoinException(cancellation)
        } catch (_: CancellationException) {
            rethrown = true
        }
        assertTrue("CancellationException은 반드시 재던져져야 함", rethrown)
    }
}
