package com.pintrip.app.map

import android.content.Context
import android.util.Log
import android.util.TypedValue
import androidx.annotation.DimenRes
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

    private fun labelTextStyle(context: Context): LabelTextStyle {
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

        return LabelTextStyle.from(textSizePx, textColor, strokeWidthPx, strokeColor)
    }

    fun createLabelStyles(context: Context, kakaoMap: KakaoMap): LabelStyles? {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_map_marker)
        val bitmap = drawable?.toBitmap()
        if (bitmap == null) {
            Log.e(TAG, "Failed to convert ic_map_marker to bitmap")
            return null
        }

        val labelStyle = LabelStyle.from(bitmap)
            .setAnchorPoint(0.5f, 1.0f)
            .setTextStyles(labelTextStyle(context))

        return kakaoMap.labelManager?.addLabelStyles(LabelStyles.from(labelStyle))
    }

    /**
     * 마스코트를 마커로 쓰는 [LabelStyles].
     *
     * - 12 장소 상세: 시안이 핀 대신 파란 마스코트를 좌표 중앙에 둔다 (`anchorY = 0.5`, 텍스트 없음).
     * - 11 방 홈의 "지도에서 보기" 강조: 주변 핀과 같은 언어를 유지하도록 좌표를 밑변에 두고
     *   장소명 텍스트도 그대로 쓴다 (`anchorY = 1.0`, 텍스트 있음).
     *
     * 원본(617x361)의 가로세로비를 유지한 채 [widthDimenResId] 폭으로 축소한다.
     */
    fun createMascotLabelStyles(
        context: Context,
        kakaoMap: KakaoMap,
        @DimenRes widthDimenResId: Int,
        anchorY: Float,
        withText: Boolean,
    ): LabelStyles? {
        val drawable = ContextCompat.getDrawable(context, R.drawable.img_mascot_blue)
        if (drawable == null) {
            Log.e(TAG, "Failed to load img_mascot_blue")
            return null
        }

        val widthPx = context.resources.getDimensionPixelSize(widthDimenResId)
        val intrinsicWidth = drawable.intrinsicWidth
        val intrinsicHeight = drawable.intrinsicHeight
        val heightPx = if (intrinsicWidth > 0 && intrinsicHeight > 0) {
            (widthPx.toFloat() * intrinsicHeight / intrinsicWidth).toInt()
        } else {
            widthPx
        }
        if (widthPx <= 0 || heightPx <= 0) {
            Log.e(TAG, "Invalid mascot marker size: ${widthPx}x$heightPx")
            return null
        }

        val bitmap = drawable.toBitmap(widthPx, heightPx)
        var labelStyle = LabelStyle.from(bitmap).setAnchorPoint(0.5f, anchorY)
        if (withText) {
            labelStyle = labelStyle.setTextStyles(labelTextStyle(context))
        }

        return kakaoMap.labelManager?.addLabelStyles(LabelStyles.from(labelStyle))
    }
}
