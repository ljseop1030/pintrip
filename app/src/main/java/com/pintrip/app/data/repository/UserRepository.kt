package com.pintrip.app.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.pintrip.app.data.model.User
import kotlinx.coroutines.tasks.await

/**
 * Firestore `users/{uid}` 컬렉션을 관리하는 Repository.
 */
open class UserRepository(
    db: FirebaseFirestore? = null,
    auth: FirebaseAuth? = null,
) {
    private val db: FirebaseFirestore by lazy { db ?: FirebaseFirestore.getInstance() }
    private val auth: FirebaseAuth by lazy { auth ?: FirebaseAuth.getInstance() }
    companion object {
        val AVATAR_COLORS = listOf(
            "#3A4A90", "#FF5722", "#4CAF50", "#9C27B0",
            "#E91E63", "#00BCD4", "#FF9800", "#3F51B5",
        )
    }

    /**
     * 지정한 UID 사용자의 프로필 문서를 안전하게 생성합니다.
     *
     * - 현재 로그인된 사용자의 UID 및 이메일과 일치하는지 검증합니다.
     * - Transaction을 사용해 문서가 존재하지 않는 경우에만 생성하고, 이미 존재하는 경우 덮어쓰지 않고 기존 정보를 보존합니다.
     */
    suspend fun createUserProfile(
        uid: String,
        nickname: String,
        email: String,
        avatarColor: String = AVATAR_COLORS.random(),
    ) {
        val currentUser = auth.currentUser
        checkNotNull(currentUser) { "로그인된 사용자 정보가 없습니다. (Auth currentUser is null)" }
        require(uid == currentUser.uid) { "요청 UID($uid)가 현재 로그인된 사용자 UID(${currentUser.uid})와 일치하지 않습니다." }
        require(nickname.isNotBlank()) { "닉네임은 비어 있을 수 없습니다." }

        val authEmail = currentUser.email ?: email
        require(authEmail.isNotBlank()) { "이메일 정보가 존재하지 않습니다." }

        val userRef = db.collection("users").document(uid)

        db.runTransaction { transaction ->
            val snapshot = transaction.get(userRef)
            if (!snapshot.exists()) {
                val newUser = User(
                    nickname = nickname.trim(),
                    email = authEmail.trim(),
                    avatarColor = avatarColor,
                    createdAt = null, // @get:ServerTimestamp에 의해 서버 시각 자동 할당
                )
                transaction.set(userRef, newUser)
            }
        }.await()
    }

    /**
     * 지정한 UID의 사용자 프로필을 조회합니다.
     *
     * 프로필이 존재하면 [User], 없으면 null을 반환합니다. (계약에 따른 다른 사용자의 프로필 조회 유지)
     */
    suspend fun getUserProfile(uid: String, source: Source = Source.DEFAULT): User? {
        require(uid.isNotBlank()) { "uid는 비어 있을 수 없습니다." }

        val snapshot = db.collection("users").document(uid).get(source).await()
        return if (snapshot.exists()) {
            snapshot.toObject(User::class.java)
        } else {
            null
        }
    }
}
