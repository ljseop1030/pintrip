package com.pintrip.app.data.repository

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.pintrip.app.data.model.InviteCode
import com.pintrip.app.data.model.Member
import com.pintrip.app.data.model.Room
import com.pintrip.app.data.model.User
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom

/**
 * Firestore `rooms` 및 `inviteCodes` 컬렉션을 관리하는 Repository.
 *
 * docs/data-contract.md 및 AGENTS.md 규격을 준수합니다:
 * 1. 방 생성 시 6자리 초대 코드(0, O, 1, I 제외)를 자동 생성합니다.
 * 2. Firestore Transaction 내에서 초대 코드 중복 여부를 확인하며, 충돌 시 재시도합니다.
 * 3. 단일 Transaction 내에서 `rooms/{roomId}`와 `inviteCodes/{code}`를 원자적으로 동시 작성(Set)합니다.
 * 4. 실제 로그인한 사용자의 `users/{uid}` 프로필(nickname, avatarColor)을 읽어 멤버 정보로 사용하며,
 *    프로필 누락/조회 실패 시 임의 기본값으로 덮지 않고 예외를 발생시킵니다.
 * 5. `getMyRooms`는 `memberIds` array-contains 조건 및 `updatedAt` 내림차순 정렬을 사용합니다.
 * 6. `lastSeenAt`은 방 생성 시점에는 null로 두고, 이번 단계에서 읽음 처리를 수행하지 않습니다.
 * 7. `updatedAt`은 생성 시점에만 서버 시각으로 초기화합니다.
 */
class RoomRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val userRepository: UserRepository = UserRepository(db, auth),
) {

    companion object {
        // 0, O, 1, I를 제외한 6자리 초대 코드용 문자셋 (총 32자)
        const val INVITE_CODE_CHARSET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        const val INVITE_CODE_LENGTH = 6
        private const val MAX_COLLISION_RETRIES = 5

        val VALID_ICONS = setOf("sea", "food", "nature", "city", "healing")

        /**
         * 0, O, 1, I가 제외된 6자리 초대 코드를 무작위 생성합니다.
         */
        fun generateInviteCode(random: java.util.Random = SecureRandom()): String {
            val sb = java.lang.StringBuilder(INVITE_CODE_LENGTH)
            for (i in 0 until INVITE_CODE_LENGTH) {
                val index = random.nextInt(INVITE_CODE_CHARSET.length)
                sb.append(INVITE_CODE_CHARSET[index])
            }
            return sb.toString()
        }
    }

    private class CodeCollisionException(message: String) : Exception(message)

    /**
     * 새로운 여행방과 초대 코드를 원자적으로 생성합니다.
     *
     * @param name 방 이름 (비어 있을 수 없음)
     * @param region 한글 지역명 (비어 있을 수 없음)
     * @param startDate 여행 시작일 (선택)
     * @param endDate 여행 종료일 (선택)
     * @param icon 메인 아이콘 ("sea", "food", "nature", "city", "healing")
     * @return 생성 완료된 [Room] 객체 (Firestore 문서 ID 포함)
     * @throws IllegalArgumentException 입력 데이터 유효성 검증 실패 시
     * @throws IllegalStateException 로그인되어 있지 않거나, 프로필이 존재하지 않을 때
     */
    suspend fun createRoom(
        name: String,
        region: String,
        startDate: Timestamp? = null,
        endDate: Timestamp? = null,
        icon: String = "sea",
    ): Room {
        require(name.isNotBlank()) { "방 이름은 비어 있을 수 없습니다." }
        require(region.isNotBlank()) { "지역 정보는 비어 있을 수 없습니다." }
        require(icon in VALID_ICONS) { "유효하지 않은 아이콘 종류입니다: $icon (허용: $VALID_ICONS)" }
        if (startDate != null && endDate != null) {
            require(endDate.compareTo(startDate) >= 0) { "여행 종료일은 시작일보다 이전일 수 없습니다." }
        }

        val currentUser = auth.currentUser
        checkNotNull(currentUser) { "로그인된 사용자 정보가 없습니다. (Auth currentUser is null)" }
        val currentUid = currentUser.uid

        var attempts = 0
        while (attempts < MAX_COLLISION_RETRIES) {
            attempts++
            val candidateCode = generateInviteCode()

            try {
                return executeCreateRoomTransaction(
                    currentUid = currentUid,
                    candidateCode = candidateCode,
                    name = name.trim(),
                    region = region.trim(),
                    startDate = startDate,
                    endDate = endDate,
                    icon = icon,
                )
            } catch (_: CodeCollisionException) {
                if (attempts >= MAX_COLLISION_RETRIES) {
                    throw IllegalStateException("초대 코드 생성 중 지속적인 충돌이 발생했습니다. 다시 시도해 주세요.")
                }
            }
        }

        throw IllegalStateException("방 생성에 실패했습니다.")
    }

    private suspend fun executeCreateRoomTransaction(
        currentUid: String,
        candidateCode: String,
        name: String,
        region: String,
        startDate: Timestamp?,
        endDate: Timestamp?,
        icon: String,
    ): Room {
        val userRef = db.collection("users").document(currentUid)
        val inviteCodeRef = db.collection("inviteCodes").document(candidateCode)
        val roomRef = db.collection("rooms").document()

        return db.runTransaction { transaction ->
            // 1. Read: 사용자 프로필 읽기 및 존재/유효성 검증 (임의 기본값 덮어쓰기 절대 금지)
            val userSnapshot = transaction.get(userRef)
            if (!userSnapshot.exists()) {
                throw IllegalStateException("사용자 프로필 문서를 찾을 수 없습니다: $currentUid")
            }

            val userProfile = userSnapshot.toObject(User::class.java)
            checkNotNull(userProfile) { "사용자 프로필 변환에 실패했습니다: $currentUid" }
            check(userProfile.nickname.isNotBlank()) { "프로필 닉네임이 비어 있습니다." }
            check(userProfile.avatarColor.isNotBlank()) { "프로필 아바타 색상이 비어 있습니다." }

            // 2. Read: 초대 코드 문서 존재 여부 확인 (충돌 검사)
            val inviteCodeSnapshot = transaction.get(inviteCodeRef)
            if (inviteCodeSnapshot.exists()) {
                throw CodeCollisionException("초대 코드 충돌: $candidateCode")
            }

            // 3. Write 준비: Member 및 Room, InviteCode 생성
            val member = Member(
                nickname = userProfile.nickname,
                avatarColor = userProfile.avatarColor,
                lastSeenAt = null, // 이번 단계에서는 읽음 처리하지 않음
            )

            val newRoom = Room(
                id = roomRef.id,
                name = name,
                region = region,
                startDate = startDate,
                endDate = endDate,
                icon = icon,
                inviteCode = candidateCode,
                ownerId = currentUid,
                memberIds = listOf(currentUid),
                members = mapOf(currentUid to member),
                placeCount = 0,
                createdAt = null, // @get:ServerTimestamp 서버 시각 지정
                updatedAt = null, // @get:ServerTimestamp 서버 시각 지정 (생성 시 초기화)
            )

            val newInviteCode = InviteCode(
                roomId = roomRef.id,
                createdAt = null,
            )

            // 4. Write: rooms 문서와 inviteCodes 문서 동시 원자적 Set
            transaction.set(roomRef, newRoom)
            transaction.set(inviteCodeRef, newInviteCode)

            newRoom
        }.await()
    }

    /**
     * 로그인한 사용자가 참여 중인 방 목록을 `updatedAt` 내림차순(최신순)으로 조회합니다.
     *
     * @param source 데이터 소스 ([Source.DEFAULT], [Source.SERVER], [Source.CACHE])
     * @return 최신 활동순으로 정렬된 [Room] 목록 (문서 ID가 `id` 필드에 바인딩됨)
     * @throws IllegalStateException 사용자가 로그인되어 있지 않은 경우
     */
    suspend fun getMyRooms(source: Source = Source.DEFAULT): List<Room> {
        val currentUser = auth.currentUser
        checkNotNull(currentUser) { "로그인된 사용자 정보가 없습니다. (Auth currentUser is null)" }
        val currentUid = currentUser.uid

        val snapshot = db.collection("rooms")
            .whereArrayContains("memberIds", currentUid)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .get(source)
            .await()

        return snapshot.documents.mapNotNull { doc ->
            doc.toObject(Room::class.java)?.apply {
                id = doc.id
            }
        }
    }

    /**
     * 지정한 방 ID의 방 문서를 1회 조회합니다.
     *
     * @param roomId 방 ID (Firestore 문서 ID)
     * @param source 데이터 소스 ([Source.DEFAULT], [Source.SERVER], [Source.CACHE])
     * @return 방 정보가 존재하면 [Room], 없으면 null
     */
    suspend fun getRoom(roomId: String, source: Source = Source.DEFAULT): Room? {
        require(roomId.isNotBlank()) { "roomId는 비어 있을 수 없습니다." }

        val snapshot = db.collection("rooms").document(roomId).get(source).await()
        return if (snapshot.exists()) {
            snapshot.toObject(Room::class.java)?.apply {
                id = snapshot.id
            }
        } else {
            null
        }
    }

    /**
     * 초대 코드를 입력하여 여행방에 가입하거나, 이미 참여 중인 경우 해당 방 정보를 반환합니다.
     *
     * docs/data-contract.md 및 AGENTS.md 규격을 준수합니다:
     * 1. 입력된 코드의 앞뒤 공백을 제거하고 대문자로 정규화합니다 (`code.trim().uppercase()`).
     * 2. 가입 전 비멤버 상태에서 `rooms/{roomId}` 문서를 직접 읽지 않습니다.
     * 3. 이미 참여 중인지 여부는 `getMyRooms(Source.SERVER)` 조회를 통해 확인하며, 기존 참여 방이면 정보 변경 없이 해당 방을 바로 반환합니다.
     * 4. `inviteCodes/{code}` 단건 조회를 통해 `roomId`를 얻고, `inviteCodes/{code}/joinProofs/{uid}` 증빙 문서 생성과 `rooms/{roomId}` 멤버 추가 및 `updatedAt` 서버 시각 갱신을 단일 WriteBatch로 원자적 실행합니다.
     * 5. 예외 발생 시 서버 멤버십을 재확인하여 동시 요청으로 이미 가입이 성공한 경우 방 정보를 정상 반환합니다.
     *
     * @param code 6자리 초대 코드
     * @return 가입 완료되거나 기존 참여 중인 [Room] 객체
     * @throws IllegalArgumentException 초대 코드가 유효하지 않거나, 코드가 존재하지 않는 경우
     * @throws IllegalStateException 로그인되어 있지 않거나, 프로필이 없거나, 권한이 거부된 경우
     */
    suspend fun joinRoomWithCode(code: String): Room {
        val normalizedCode = code.trim().uppercase()
        require(normalizedCode.matches(Regex("^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{6}$"))) {
            "유효하지 않은 초대 코드 형식입니다. 6자리 영문 대문자 및 숫자를 입력해 주세요."
        }

        val currentUser = auth.currentUser
        checkNotNull(currentUser) { "로그인된 사용자 정보가 없습니다. (Auth currentUser is null)" }
        val currentUid = currentUser.uid

        // 1. 이미 멤버인 방인지 본인 허용 경로(getMyRooms)로 사전 확인
        val existingRooms = getMyRooms(Source.SERVER)
        val existingRoom = existingRooms.find { it.inviteCode == normalizedCode }
        if (existingRoom != null) {
            return existingRoom
        }

        // 2. 초대 코드 단건 조회 (비멤버도 get 허용)
        val inviteSnapshot = db.collection("inviteCodes").document(normalizedCode).get(Source.SERVER).await()
        if (!inviteSnapshot.exists()) {
            throw IllegalArgumentException("존재하지 않는 초대 코드입니다.")
        }

        val roomId = inviteSnapshot.getString("roomId")
        check(!roomId.isNullOrBlank()) { "초대 코드에 매핑된 방 정보가 올바르지 않습니다." }

        // 3. 사용자 프로필 조회
        val userProfile = userRepository.getUserProfile(currentUid, Source.DEFAULT)
        checkNotNull(userProfile) { "사용자 프로필 문서를 찾을 수 없습니다: $currentUid" }
        check(userProfile.nickname.isNotBlank()) { "프로필 닉네임이 비어 있습니다." }
        check(userProfile.avatarColor.isNotBlank()) { "프로필 아바타 색상이 비어 있습니다." }

        val member = Member(
            nickname = userProfile.nickname,
            avatarColor = userProfile.avatarColor,
            lastSeenAt = null,
        )

        // 4. 원자적 가입 처리: inviteCodes/{code}/joinProofs/{uid} 생성 + rooms/{roomId} 멤버 추가 및 updatedAt 갱신
        val batch = db.batch()
        val proofRef = db.collection("inviteCodes")
            .document(normalizedCode)
            .collection("joinProofs")
            .document(currentUid)
        val roomRef = db.collection("rooms").document(roomId)

        batch.set(proofRef, mapOf("joinedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()))
        batch.update(
            roomRef,
            "memberIds", com.google.firebase.firestore.FieldValue.arrayUnion(currentUid),
            "members.$currentUid", member,
            "updatedAt", com.google.firebase.firestore.FieldValue.serverTimestamp(),
        )

        try {
            batch.commit().await()
            val joinedRoom = getRoom(roomId, Source.SERVER)
            checkNotNull(joinedRoom) { "방 가입 완료 후 방 정보를 불러오지 못했습니다: $roomId" }
            return joinedRoom
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                throw e
            }
            // 동시 요청 또는 네트워크 재시도로 이미 서버에서 가입 처리되었는지 안전하게 재확인
            val recheckedRoom = try {
                val recheckedRooms = getMyRooms(Source.SERVER)
                recheckedRooms.find { it.id == roomId }
            } catch (recheckError: Exception) {
                if (recheckError is kotlinx.coroutines.CancellationException) {
                    throw recheckError
                }
                null
            }

            if (recheckedRoom != null) {
                return recheckedRoom
            }

            if (e is com.google.firebase.firestore.FirebaseFirestoreException &&
                e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
            ) {
                throw IllegalStateException("방 가입 권한이 없거나 요청이 거부되었습니다.", e)
            }
            throw e
        }
    }
}
