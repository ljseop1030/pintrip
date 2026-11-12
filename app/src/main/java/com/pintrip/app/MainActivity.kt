package com.pintrip.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.Source
import com.pintrip.app.auth.TestLoginActivity
import com.pintrip.app.data.repository.UserRepository
import com.pintrip.app.databinding.ActivityMainBinding
import com.pintrip.app.room.RoomListActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 앱진입 메인 액티비티.
 *
 * Firebase Auth 세션 및 Firestore 프로필(Source.SERVER)을 확인하여
 * - 미로그인 시: [TestLoginActivity]로 이동
 * - 로그인 및 프로필 확인 시: [RoomListActivity]로 이동
 * - 로그인되어 있으나 프로필 문서 누락 시: [TestLoginActivity]로 이동하여 닉네임 복구 진행
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val userRepository: UserRepository by lazy { UserRepository(auth = auth) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.btnTestLogin.setOnClickListener {
            navigateToLogin()
        }

        val currentUser = auth.currentUser
        if (currentUser == null) {
            navigateToLogin()
        } else {
            binding.btnTestLogin.isEnabled = false
            verifyProfileAndNavigate(currentUser)
        }
    }

    private fun verifyProfileAndNavigate(user: FirebaseUser) {
        lifecycleScope.launch {
            try {
                binding.btnTestLogin.isEnabled = false
                val profile = userRepository.getUserProfile(user.uid, source = Source.SERVER)
                if (profile != null && profile.nickname.isNotBlank()) {
                    navigateToRoomList()
                } else {
                    navigateToLogin()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.btnTestLogin.isEnabled = true
                val errorMsg = e.localizedMessage ?: getString(R.string.error_profile_load_failed)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.error_profile_load_failed))
                    .setMessage(errorMsg)
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.btn_retry)) { _, _ ->
                        verifyProfileAndNavigate(user)
                    }
                    .setNegativeButton(getString(R.string.btn_logout)) { _, _ ->
                        auth.signOut()
                        navigateToLogin()
                    }
                    .show()
            }
        }
    }

    private fun navigateToRoomList() {
        val intent = Intent(this, RoomListActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun navigateToLogin() {
        val intent = Intent(this, TestLoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
