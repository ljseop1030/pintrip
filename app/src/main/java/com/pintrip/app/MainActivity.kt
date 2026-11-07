package com.pintrip.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.pintrip.app.auth.TestLoginActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: com.pintrip.app.databinding.ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = com.pintrip.app.databinding.ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        if (BuildConfig.DEBUG) {
            binding.btnTestLogin.visibility = View.VISIBLE
            binding.btnTestLogin.setOnClickListener {
                startActivity(Intent(this, TestLoginActivity::class.java))
            }
            // 최초 앱 실행(런처 실행 및 액티비티 최초 생성) 시에만 테스트 로그인 화면으로 자동 진입
            val isInitialLauncherLaunch = savedInstanceState == null &&
                    intent?.action == Intent.ACTION_MAIN &&
                    intent.hasCategory(Intent.CATEGORY_LAUNCHER)

            if (isInitialLauncherLaunch) {
                startActivity(Intent(this, TestLoginActivity::class.java))
            }
        } else {
            binding.btnTestLogin.visibility = View.GONE
        }
    }
}
