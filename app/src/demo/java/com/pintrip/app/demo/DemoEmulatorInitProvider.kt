package com.pintrip.app.demo

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
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

    companion object {
        private const val TAG = "DemoEmulatorInit"
        private const val EXPECTED_PROJECT_ID = "demo-pintrip"
        private const val EMULATOR_HOST = "10.0.2.2"
        private const val FIRESTORE_PORT = 8080
        private const val AUTH_PORT = 9099
    }

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            val app = if (FirebaseApp.getApps(ctx).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setProjectId(EXPECTED_PROJECT_ID)
                    .setApplicationId("1:123456789012:android:demo1234567890")
                    .setApiKey("AIzaSyDemoApiKeyForLocalTestingOnly123")
                    .build()
                FirebaseApp.initializeApp(ctx, options)
            } else {
                FirebaseApp.getInstance()
            }

            val projectId = app.options.projectId
            if (projectId != EXPECTED_PROJECT_ID) {
                val errorMsg = "Demo Variant는 $EXPECTED_PROJECT_ID 프로젝트 ID만 지원합니다. (현재: $projectId)"
                Log.e(TAG, errorMsg)
                throw IllegalStateException(errorMsg)
            }

            try {
                FirebaseFirestore.getInstance(app).useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
                FirebaseAuth.getInstance(app).useEmulator(EMULATOR_HOST, AUTH_PORT)
                Log.i(TAG, "Demo Emulator 연결 완료 - Project: $projectId, Host: $EMULATOR_HOST, AuthPort: $AUTH_PORT, FirestorePort: $FIRESTORE_PORT")
            } catch (e: Exception) {
                val errorMsg = "Firebase 에뮬레이터 연결 실패 (Host: $EMULATOR_HOST): ${e.localizedMessage}"
                Log.e(TAG, errorMsg, e)
                throw IllegalStateException(errorMsg, e)
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
