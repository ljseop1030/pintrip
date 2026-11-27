package com.pintrip.app.room

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.firebase.firestore.FirebaseFirestoreException
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

open class FakeRoomRepository : RoomRepository(null, null, null) {
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

        // 1. 실제 joinRoomWithCode() 호출로 NewJoin 가입 완료 상태 생성
        fakeRoomRepo.resultToReturn = JoinRoomResult.NewJoin(sampleRoom)
        viewModel.joinRoomWithCode("K7P2QX")

        assertEquals(1, fakeRoomRepo.joinRoomCallCount)
        assertEquals(JoinRoomResult.NewJoin(sampleRoom), viewModel.joinSuccessResult.value)

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

        // 취소 직후 오래된 완료 버튼 콜백 호출 시 이동 이벤트 미발생 검증
        viewModel.onViewPlacesFromJoinSuccess("room_123")
        assertNull("취소 후 오래된 완료 버튼 콜백 호출 시 이동 이벤트가 발생하지 않아야 함", viewModel.navigateToPlaceList.value?.getContentIfNotHandled())

        // 4. 취소 후 정상적으로 카드 클릭 허용
        viewModel.onRoomCardClick(sampleRoom)
        assertEquals("room_123", viewModel.navigateToPlaceList.value?.getContentIfNotHandled())
    }

    @Test
    fun scenario2_launchFailurePreservesResultAllowsRetryWithoutReJoinAndHandlesRapidClicks() {
        val fakeRoomRepo = FakeRoomRepository()
        val fakeUserRepo = FakeUserRepository()
        val viewModel = RoomListViewModel(fakeRoomRepo, fakeUserRepo, null)

        // 1. 실제 가입으로 완료 상태 생성
        fakeRoomRepo.resultToReturn = JoinRoomResult.NewJoin(sampleRoom)
        viewModel.joinRoomWithCode("K7P2QX")
        assertEquals(1, fakeRoomRepo.joinRoomCallCount)

        // 2. 장소 목록 보기 선택
        viewModel.onViewPlacesFromJoinSuccess("room_123")
        assertEquals("room_123", viewModel.navigateToPlaceList.value?.getContentIfNotHandled())

        // 3. 이동 중 연타 시 중복 이벤트 차단
        viewModel.onViewPlacesFromJoinSuccess("room_123")
        assertNull("이동 중 연타 시 두 번째 이벤트는 없어야 함", viewModel.navigateToPlaceList.value?.getContentIfNotHandled())

        // 4. launch 예외 발생 시 (onPlaceListLaunchFailed)
        viewModel.onPlaceListLaunchFailed()

        // 결과 보존 및 실패 토스트 발송 확인
        assertEquals(JoinRoomResult.NewJoin(sampleRoom), viewModel.joinSuccessResult.value)
        assertEquals(R.string.place_list_error_cannot_open, viewModel.toastEvent.value?.getContentIfNotHandled())

        // 5. 사용자가 완료창에서 다시 [장소 목록 보기] 재시도
        viewModel.onViewPlacesFromJoinSuccess("room_123")
        assertEquals("동일한 roomId로 1회 재시도 이동 이벤트 발생해야 함", "room_123", viewModel.navigateToPlaceList.value?.getContentIfNotHandled())

        // Repository의 가입 batch 메서드가 재호출되지 않고 호출 횟수가 1로 유지되어야 함
        assertEquals(1, fakeRoomRepo.joinRoomCallCount)
    }

    @Test
    fun scenario3_launchSuccessClearsResultAndUserDismissDoesNotReFireEvents() {
        val fakeRoomRepo = FakeRoomRepository()
        val fakeUserRepo = FakeUserRepository()
        val viewModel = RoomListViewModel(fakeRoomRepo, fakeUserRepo, null)

        // 1. 실제 가입으로 완료 상태 생성 후 이동 선택
        fakeRoomRepo.resultToReturn = JoinRoomResult.NewJoin(sampleRoom)
        viewModel.joinRoomWithCode("K7P2QX")
        viewModel.onViewPlacesFromJoinSuccess("room_123")
        assertEquals("room_123", viewModel.navigateToPlaceList.value?.getContentIfNotHandled())

        // 2. launch 성공
        viewModel.onPlaceListLaunchSuccess()
        assertNull("launch 성공 시 joinSuccessResult는 null이어야 함", viewModel.joinSuccessResult.value)

        // 3. 실제 장소 화면에서 복귀한 것처럼 잠금 해제
        viewModel.resetNavigation()

        // 4. 오래된 완료 버튼 콜백 호출 시 결과가 null이므로 무효한 이동 이벤트 발생 없음 검증
        viewModel.onViewPlacesFromJoinSuccess("room_123")
        assertNull("성공 후 오래된 버튼 콜백 호출 시 이동 이벤트가 발생하지 않아야 함", viewModel.navigateToPlaceList.value?.getContentIfNotHandled())

        viewModel.onDismissJoinSuccess()
        viewModel.onViewPlacesFromJoinSuccess("room_123")
        assertNull(viewModel.navigateToPlaceList.value?.getContentIfNotHandled())
    }

    @Test
    fun scenario4_creatorAndExistingMemberResults_directCodeEntryShowsInlineNotice() {
        val fakeRoomRepo = FakeRoomRepository()
        val fakeUserRepo = FakeUserRepository()
        val viewModel = RoomListViewModel(fakeRoomRepo, fakeUserRepo, null)

        // 수동 클릭으로 직접 코드 입력 진입
        viewModel.onJoinCodeClick()

        // 1. 직접 입력시 Creator 반환 -> 인라인 에러 표시, joinSuccessResult null 유지
        fakeRoomRepo.resultToReturn = JoinRoomResult.Creator(sampleRoom)
        viewModel.joinRoomWithCode(null, "K7P2QX")

        assertNull("직접 입력 시 Creator 반환은 joinSuccessResult가 null이어야 함", viewModel.joinSuccessResult.value)
        assertEquals("방장 안내 문구가 설정되어야 함", R.string.notice_join_own_room, viewModel.joinErrorMessageResId.value)

        // 2. 직접 입력시 ExistingMember 반환 -> 인라인 에러 표시, joinSuccessResult null 유지
        fakeRoomRepo.resultToReturn = JoinRoomResult.ExistingMember(sampleRoom)
        viewModel.joinRoomWithCode(null, "B8R3SY")

        assertNull("직접 입력 시 ExistingMember 반환은 joinSuccessResult가 null이어야 함", viewModel.joinSuccessResult.value)
        assertEquals("기존 멤버 안내 문구가 설정되어야 함", R.string.notice_already_joined_room, viewModel.joinErrorMessageResId.value)
    }

    @Test
    fun scenario4b_creatorAndExistingMemberResults_deepLinkShowsSuccessDialog() {
        val fakeRoomRepo = FakeRoomRepository()
        val fakeUserRepo = FakeUserRepository()
        val viewModel = RoomListViewModel(fakeRoomRepo, fakeUserRepo, null)

        // 딥링크 수신으로 진입
        viewModel.handleDeepLinkCode(null, "K7P2QX")

        // 1. 딥링크 경로 Creator 반환 -> completion dialog 표시
        fakeRoomRepo.resultToReturn = JoinRoomResult.Creator(sampleRoom)
        viewModel.joinRoomWithCode(null, "K7P2QX")

        assertEquals(JoinRoomResult.Creator(sampleRoom), viewModel.joinSuccessResult.value)
        assertFalse("Creator 처리 후 입력을 위한 대화창은 닫혀야 함", viewModel.isJoinDialogOpen.value == true)

        viewModel.onDismissJoinSuccess()

        // 2. 딥링크 경로 ExistingMember 반환 -> completion dialog 표시
        viewModel.handleDeepLinkCode(null, "B8R3SY")
        fakeRoomRepo.resultToReturn = JoinRoomResult.ExistingMember(sampleRoom)
        viewModel.joinRoomWithCode(null, "B8R3SY")

        assertEquals(JoinRoomResult.ExistingMember(sampleRoom), viewModel.joinSuccessResult.value)
        assertFalse("ExistingMember 처리 후 입력을 위한 대화창은 닫혀야 함", viewModel.isJoinDialogOpen.value == true)
    }

    @Test
    fun scenario6_checkPendingInvite_preservesCodeInStorageUntilConsumedOrCanceled() {
        val fakeRoomRepo = FakeRoomRepository()
        val fakeUserRepo = FakeUserRepository()
        val viewModel = RoomListViewModel(fakeRoomRepo, fakeUserRepo, null)

        val fakePrefs = FakeSharedPreferences()
        PendingInviteManager.preferenceOverride = fakePrefs

        try {
            PendingInviteManager.savePendingCode(null, "K7P2QX")

            viewModel.checkPendingInvite(null)

            assertTrue("대기 중인 코드가 있을 경우 초대 대화창이 오픈되어야 함", viewModel.isJoinDialogOpen.value == true)
            assertEquals("K7P2QX", viewModel.joinCodeInput.value)
            // 단순히 참여창을 띄우는 것만으로는 저장소에서 삭제하지 않음 (프로세스 재생성 복원 보장)
            assertEquals("K7P2QX", PendingInviteManager.getPendingCode(null))

            // 사용자가 명시적으로 취소한 경우 저장소에서 삭제
            viewModel.onJoinDialogDismissed(null)
            assertNull("명시적 취소 후 저장소의 대기 코드는 삭제되어야 함", PendingInviteManager.getPendingCode(null))
        } finally {
            PendingInviteManager.preferenceOverride = null
        }
    }

    @Test
    fun scenario7_handleDeepLinkCode_opensDialogWithCode() {
        val fakeRoomRepo = FakeRoomRepository()
        val fakeUserRepo = FakeUserRepository()
        val viewModel = RoomListViewModel(fakeRoomRepo, fakeUserRepo, null)

        val fakePrefs = FakeSharedPreferences()
        PendingInviteManager.preferenceOverride = fakePrefs

        try {
            viewModel.handleDeepLinkCode(null, "b8r3sy")

            assertTrue("핫 딥링크 수신 시 초대 대화창이 오픈되어야 함", viewModel.isJoinDialogOpen.value == true)
            assertEquals("B8R3SY", viewModel.joinCodeInput.value)
            assertEquals("B8R3SY", PendingInviteManager.getPendingCode(null))
        } finally {
            PendingInviteManager.preferenceOverride = null
        }
    }

    @Test
    fun scenario8_deepLinkDuringIncorporateJoin_doesNotOverwriteInProgressJoinAndPreservesSecondCode() {
        val fakeRoomRepo = object : FakeRoomRepository() {
            override suspend fun joinRoomWithCode(code: String): JoinRoomResult {
                kotlinx.coroutines.delay(1000)
                return super.joinRoomWithCode(code)
            }
        }
        val fakeUserRepo = FakeUserRepository()
        val viewModel = RoomListViewModel(fakeRoomRepo, fakeUserRepo, null)

        val fakePrefs = FakeSharedPreferences()
        PendingInviteManager.preferenceOverride = fakePrefs

        try {
            val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
            Dispatchers.setMain(testDispatcher)

            viewModel.handleDeepLinkCode(null, "K7P2QX")
            viewModel.joinRoomWithCode(null, "K7P2QX")
            testDispatcher.scheduler.runCurrent()

            assertTrue("가입 실행 중이므로 isJoining은 true여야 함", viewModel.isJoining.value == true)
            assertEquals("K7P2QX", viewModel.joinCodeInput.value)

            // 방 A 가입 시도 중 방 B 딥링크 수신
            viewModel.handleDeepLinkCode(null, "NEW234")

            // 진행 중인 방 A 가입 입력을 덮어쓰지 않고, 방 B 코드는 저장소에 보존되어야 함
            assertEquals("K7P2QX", viewModel.joinCodeInput.value)
            assertEquals("NEW234", PendingInviteManager.getPendingCode(null))

            // 방 A 가입 완료 처리
            testDispatcher.scheduler.advanceUntilIdle()

            // 방 A 가입 완료 결과가 표시되고, 방 B 코드는 삭제되지 않고 보존됨을 검증
            assertEquals("NEW234", PendingInviteManager.getPendingCode(null))
            assertTrue(viewModel.joinSuccessResult.value != null)

            // 사용자가 방 A 완료창을 닫을 때 방 B 참여창이 즉각 연결되어 표시됨을 검증
            viewModel.onDismissJoinSuccess(null)
            assertNull(viewModel.joinSuccessResult.value)
            assertTrue("방 A 완료창 닫기 후 방 B 참여창이 열려야 함", viewModel.isJoinDialogOpen.value == true)
            assertEquals("NEW234", viewModel.joinCodeInput.value)
        } finally {
            PendingInviteManager.preferenceOverride = null
        }
    }

    @Test
    fun scenario11_deepLinkDuringCompletionDialog_preservesResultAAndShowsBOnDismiss() {
        val fakeRoomRepo = FakeRoomRepository()
        val fakeUserRepo = FakeUserRepository()
        val viewModel = RoomListViewModel(fakeRoomRepo, fakeUserRepo, null)

        val fakePrefs = FakeSharedPreferences()
        PendingInviteManager.preferenceOverride = fakePrefs

        try {
            val roomA = Room(id = "room_A", name = "A 여행", inviteCode = "K7P2QX")
            fakeRoomRepo.resultToReturn = JoinRoomResult.NewJoin(roomA)

            // 방 A 가입 완료 상태
            viewModel.handleDeepLinkCode(null, "K7P2QX")
            viewModel.joinRoomWithCode(null, "K7P2QX")
            assertEquals(JoinRoomResult.NewJoin(roomA), viewModel.joinSuccessResult.value)

            // 방 A 완료창 표시 중 방 B 딥링크 수신
            viewModel.handleDeepLinkCode(null, "NEW234")

            // 완료창 A 유지, B 코드는 저장소에 보존
            assertEquals(JoinRoomResult.NewJoin(roomA), viewModel.joinSuccessResult.value)
            assertEquals("NEW234", PendingInviteManager.getPendingCode(null))

            // 사용자가 방 A 완료창을 [목록으로] 닫음
            viewModel.onDismissJoinSuccess(null)

            // 완료창 A 닫힌 후 방 B 참여창 오픈
            assertNull(viewModel.joinSuccessResult.value)
            assertTrue("방 A 완료창 닫기 후 방 B 참여창이 열려야 함", viewModel.isJoinDialogOpen.value == true)
            assertEquals("NEW234", viewModel.joinCodeInput.value)
        } finally {
            PendingInviteManager.preferenceOverride = null
        }
    }

    @Test
    fun scenario12_selectingViewPlacesOnCompletionA_doesNotShowBImmediately_showsBOnReturnToRoomList() {
        val fakeRoomRepo = FakeRoomRepository()
        val fakeUserRepo = FakeUserRepository()
        val viewModel = RoomListViewModel(fakeRoomRepo, fakeUserRepo, null)

        val fakePrefs = FakeSharedPreferences()
        PendingInviteManager.preferenceOverride = fakePrefs

        try {
            val roomA = Room(id = "room_A", name = "A 여행", inviteCode = "K7P2QX")
            fakeRoomRepo.resultToReturn = JoinRoomResult.NewJoin(roomA)

            // 방 A 가입 완료 상태
            viewModel.handleDeepLinkCode(null, "K7P2QX")
            viewModel.joinRoomWithCode(null, "K7P2QX")

            // 방 A 완료창 표시 중 방 B 딥링크 수신
            viewModel.handleDeepLinkCode(null, "NEW234")

            // [장소 목록 보기] 선택 -> 장소 화면 이동 성공
            viewModel.onViewPlacesFromJoinSuccess("room_A")
            viewModel.onPlaceListLaunchSuccess()

            // 장소 화면으로 이동하는 순간에는 방 B 참여창이 바로 뜨지 않음
            assertNull(viewModel.joinSuccessResult.value)
            assertFalse("장소 화면 이동 시점에 방 B 참여창이 즉시 뜨면 안 됨", viewModel.isJoinDialogOpen.value == true)
            assertEquals("NEW234", PendingInviteManager.getPendingCode(null))

            // 장소 화면에서 방 목록으로 복귀하여 checkPendingInvite 호출 시 방 B 참여창 오픈
            viewModel.checkPendingInvite(null)
            assertTrue("방 목록 복귀 시 방 B 참여창이 열려야 함", viewModel.isJoinDialogOpen.value == true)
            assertEquals("NEW234", viewModel.joinCodeInput.value)
        } finally {
            PendingInviteManager.preferenceOverride = null
        }
    }

    @Test
    fun scenario9_unrecoverableCodeNotFoundClearsPendingCode_whileNetworkErrorPreservesIt() {
        val fakeRoomRepo = FakeRoomRepository()
        val fakeUserRepo = FakeUserRepository()
        val viewModel = RoomListViewModel(fakeRoomRepo, fakeUserRepo, null)

        val fakePrefs = FakeSharedPreferences()
        PendingInviteManager.preferenceOverride = fakePrefs

        try {
            // 1. CodeNotFound (서버 무효 코드) 발생 시 저장소에서 삭제 검증
            PendingInviteManager.savePendingCode(null, "K7P2QX")
            fakeRoomRepo.exceptionToThrow = RoomJoinException.CodeNotFound()

            viewModel.joinRoomWithCode(null, "K7P2QX")
            assertNull("CodeNotFound 발생 시 임시 저장소에서 코드 삭제되어야 함", PendingInviteManager.getPendingCode(null))

            // 2. NetworkUnavailable (일시적 네트워크 에러) 발생 시 저장소에 유지 검증
            PendingInviteManager.savePendingCode(null, "B8R3SY")
            fakeRoomRepo.exceptionToThrow = RoomJoinException.NetworkUnavailable(Throwable("Net error"))

            viewModel.joinRoomWithCode(null, "B8R3SY")
            assertEquals("NetworkUnavailable 발생 시 재시도를 위해 임시 저장소에 코드가 보존되어야 함", "B8R3SY", PendingInviteManager.getPendingCode(null))
        } finally {
            PendingInviteManager.preferenceOverride = null
        }
    }

    @Test
    fun scenario5_exceptionMappingBoundaryPreservesCauseAndReThrowsCancellation() {
        val dummyRepo = RoomRepository(null, null, null)

        // 1. FirebaseFirestoreException UNAVAILABLE -> RoomJoinException.NetworkUnavailable (cause 동일성 검증)
        val firestoreUnavailable = FirebaseFirestoreException(
            "Network error",
            FirebaseFirestoreException.Code.UNAVAILABLE,
        )
        val mappedNetwork = dummyRepo.mapToRoomJoinException(firestoreUnavailable)
        assertTrue("UNAVAILABLE 코드는 NetworkUnavailable로 변환되어야 함", mappedNetwork is RoomJoinException.NetworkUnavailable)
        assertTrue("원래 예외가 cause로 보존되어야 함", mappedNetwork.cause === firestoreUnavailable)

        // 2. FirebaseFirestoreException PERMISSION_DENIED -> RoomJoinException.PermissionDenied (cause 동일성 검증)
        val firestorePermission = FirebaseFirestoreException(
            "Permission denied",
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
        )
        val mappedPermission = dummyRepo.mapToRoomJoinException(firestorePermission)
        assertTrue("PERMISSION_DENIED 코드는 PermissionDenied로 변환되어야 함", mappedPermission is RoomJoinException.PermissionDenied)
        assertTrue("원래 예외가 cause로 보존되어야 함", mappedPermission.cause === firestorePermission)

        // 3. FirebaseFirestoreException UNAAUTHENTICATED -> RoomJoinException.NotLoggedIn (cause 동일성 검증)
        val firestoreUnauth = FirebaseFirestoreException(
            "Unauthenticated",
            FirebaseFirestoreException.Code.UNAUTHENTICATED,
        )
        val mappedUnauth = dummyRepo.mapToRoomJoinException(firestoreUnauth)
        assertTrue("UNAUTHENTICATED 코드는 NotLoggedIn으로 변환되어야 함", mappedUnauth is RoomJoinException.NotLoggedIn)
        assertTrue("원래 예외가 cause로 보존되어야 함", mappedUnauth.cause === firestoreUnauth)

        // 4. FirebaseFirestoreException DEADLINE_EXCEEDED -> RoomJoinException.NetworkUnavailable (cause 동일성 검증)
        val firestoreDeadline = FirebaseFirestoreException(
            "Deadline exceeded",
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        )
        val mappedDeadline = dummyRepo.mapToRoomJoinException(firestoreDeadline)
        assertTrue("DEADLINE_EXCEEDED 코드는 NetworkUnavailable로 변환되어야 함", mappedDeadline is RoomJoinException.NetworkUnavailable)
        assertTrue("원래 예외가 cause로 보존되어야 함", mappedDeadline.cause === firestoreDeadline)

        // 5. 프로필 읽기 중 네트워크 실패(firestoreUnavailable)가 ProfileRequired로 왜곡되지 않고 NetworkUnavailable 타입 유지
        val mappedProfileNet = dummyRepo.mapToRoomJoinException(firestoreUnavailable)
        assertFalse("프로필 네트워크 에러는 ProfileRequired가 아니라 NetworkUnavailable이어야 함", mappedProfileNet is RoomJoinException.ProfileRequired)
        assertTrue(mappedProfileNet is RoomJoinException.NetworkUnavailable)

        // 5. CancellationException 재던짐 검증 (동일 객체 재던짐 확인)
        val cancellation = CancellationException("Job canceled")
        var rethrownObj: CancellationException? = null
        try {
            dummyRepo.mapToRoomJoinException(cancellation)
        } catch (e: CancellationException) {
            rethrownObj = e
        }
        assertTrue("CancellationException은 동일 객체가 재던져져야 함", rethrownObj === cancellation)

        // 6. ViewModel의 RoomJoinException 하위 타입별 한국어 리소스 ID 분류 검증
        val fakeRoomRepo = FakeRoomRepository()
        val fakeUserRepo = FakeUserRepository()
        val viewModel = RoomListViewModel(fakeRoomRepo, fakeUserRepo, null)

        fakeRoomRepo.exceptionToThrow = RoomJoinException.NotLoggedIn(firestoreUnauth)
        viewModel.joinRoomWithCode("K7P2QX")
        assertEquals(R.string.error_not_logged_in, viewModel.joinErrorMessageResId.value)

        fakeRoomRepo.exceptionToThrow = RoomJoinException.ProfileRequired("손상됨")
        viewModel.joinRoomWithCode("K7P2QX")
        assertEquals(R.string.error_profile_check_required, viewModel.joinErrorMessageResId.value)

        fakeRoomRepo.exceptionToThrow = RoomJoinException.NetworkUnavailable(firestoreUnavailable)
        viewModel.joinRoomWithCode("K7P2QX")
        assertEquals(R.string.error_network_unavailable, viewModel.joinErrorMessageResId.value)

        fakeRoomRepo.exceptionToThrow = RoomJoinException.PermissionDenied(firestorePermission)
        viewModel.joinRoomWithCode("K7P2QX")
        assertEquals(R.string.error_room_join_permission_denied, viewModel.joinErrorMessageResId.value)

        fakeRoomRepo.exceptionToThrow = RoomJoinException.CodeNotFound()
        viewModel.joinRoomWithCode("K7P2QX")
        assertEquals(R.string.error_invite_code_not_found, viewModel.joinErrorMessageResId.value)
    }

    @Test
    fun syncDeviceToken_callsRepositorySyncToken() {
        val fakeTokenRepo = FakeDeviceTokenRepository()
        val viewModel = RoomListViewModel(
            roomRepository = FakeRoomRepository(),
            userRepository = FakeUserRepository(),
            deviceTokenRepository = fakeTokenRepo
        )

        viewModel.syncDeviceToken()

        assertTrue(fakeTokenRepo.syncTokenCalled)
    }

    @Test
    fun logout_callsRepositoryCleanupToken() {
        val fakeTokenRepo = FakeDeviceTokenRepository()
        val viewModel = RoomListViewModel(
            roomRepository = FakeRoomRepository(),
            userRepository = FakeUserRepository(),
            deviceTokenRepository = fakeTokenRepo,
            currentUidProvider = { "user_owner_123" }
        )

        viewModel.logout()

        assertTrue(fakeTokenRepo.cleanupTokenCalled)
        assertEquals("user_owner_123", fakeTokenRepo.lastCleanedUid)
        assertNotNull(viewModel.navigateToLogin.value)
    }
}

open class FakeDeviceTokenRepository : com.pintrip.app.data.repository.DeviceTokenRepository(null, null, null, null) {
    var syncTokenCalled = false
    var cleanupTokenCalled = false
    var lastCleanedUid: String? = null

    override suspend fun syncToken(token: String?): Boolean {
        syncTokenCalled = true
        return true
    }

    override suspend fun cleanupTokenOnLogout(previousUid: String): Boolean {
        cleanupTokenCalled = true
        lastCleanedUid = previousUid
        return true
    }
}
