package com.pintrip.app.map

import android.content.Context
import android.util.Log
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapSdk
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.label.LabelTextStyle
import com.pintrip.app.BuildConfig
import com.pintrip.app.R
import java.util.concurrent.atomic.AtomicReference

object KakaoMapHelper {
    private const val TAG = "KakaoMapHelper"

    enum class SdkInitState {
        NOT_INITIALIZED,
        SUCCESS,
        FAILED
    }

    private val sdkInitState = AtomicReference(SdkInitState.NOT_INITIALIZED)

    fun getSdkInitState(): SdkInitState = sdkInitState.get()

    fun initSdk(context: Context): SdkInitState {
        val currentState = sdkInitState.get()
        if (currentState != SdkInitState.NOT_INITIALIZED) {
            return currentState
        }
        return try {
            KakaoMapSdk.init(context.applicationContext, BuildConfig.KAKAO_NATIVE_APP_KEY)
            sdkInitState.set(SdkInitState.SUCCESS)
            SdkInitState.SUCCESS
        } catch (e: Throwable) {
            Log.e(TAG, "KakaoMapSdk init failed: ${e.message}", e)
            sdkInitState.set(SdkInitState.FAILED)
            SdkInitState.FAILED
        }
    }

    fun createLabelStyles(context: Context, kakaoMap: KakaoMap): LabelStyles? {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_map_marker)
        val bitmap = drawable?.toBitmap()
        if (bitmap == null) {
            Log.e(TAG, "Failed to convert ic_map_marker to bitmap")
            return null
        }

        val textColor = ContextCompat.getColor(context, R.color.map_label_text)
        val strokeColor = ContextCompat.getColor(context, R.color.map_label_stroke)

        val textSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            13f,
            context.resources.displayMetrics
        ).toInt()
        val strokeWidthPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            2f,
            context.resources.displayMetrics
        ).toInt()

        val labelStyle = LabelStyle.from(bitmap)
            .setAnchorPoint(0.5f, 1.0f)
            .setTextStyles(LabelTextStyle.from(textSizePx, textColor, strokeWidthPx, strokeColor))

        return kakaoMap.labelManager?.addLabelStyles(LabelStyles.from(labelStyle))
    }
}
