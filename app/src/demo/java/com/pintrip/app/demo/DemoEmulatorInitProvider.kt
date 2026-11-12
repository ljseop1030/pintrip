package com.pintrip.app.demo

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Demo Variant 전용 Firebase 에뮬레이터 (10.0.2.2:9099, 10.0.2.2:8080) 자동 연결 Provider.
 *
 * `demo-pintrip` 프로젝트 ID를 사용하여 로컬 에뮬레이터에 연결합니다.
 * `full` (운영/기본) 변체에는 포함되지 않으므로 실서버에 전혀 영향을 주지 않습니다.
 */
class DemoEmulatorInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            val app = if (FirebaseApp.getApps(ctx).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setProjectId("demo-pintrip")
                    .setApplicationId("1:123456789012:android:demo1234567890")
                    .setApiKey("AIzaSyDemoApiKeyForLocalTestingOnly123")
                    .build()
                FirebaseApp.initializeApp(ctx, options)
            } else {
                FirebaseApp.getInstance()
            }

            try {
                FirebaseFirestore.getInstance(app).useEmulator("10.0.2.2", 8080)
                FirebaseAuth.getInstance(app).useEmulator("10.0.2.2", 9099)
            } catch (_: Exception) {
                // 이미 에뮬레이터에 연결된 경우 무시
            }
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
