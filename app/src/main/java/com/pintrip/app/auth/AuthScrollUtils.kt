package com.pintrip.app.auth

import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.widget.ScrollView

/**
 * ScrollView 내 targetView가 현재 보이는 뷰포트에 완전히 포함되었는지 계산하고,
 * 완전히 보이지 않는 경우(위/아래 잘림) 최소 이동 거리만큼 smoothScrollTo를 수행하는 확장 함수.
 */
fun ScrollView.scrollToEnsureViewFullyVisible(targetView: View, marginDp: Float = 16f) {
    if (targetView.visibility != View.VISIBLE || !targetView.isLaidOut) return

    val child = getChildAt(0) ?: return
    val maxScrollY = maxOf(0, child.height - height)

    val targetRect = Rect()
    targetView.getDrawingRect(targetRect)
    try {
        offsetDescendantRectToMyCoords(targetView, targetRect)
    } catch (_: Exception) {
        return
    }

    val marginPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        marginDp,
        resources.displayMetrics,
    ).toInt()

    val targetScrollY = computeTargetScrollY(
        scrollY = scrollY,
        scrollViewHeight = height,
        paddingTop = paddingTop,
        paddingBottom = paddingBottom,
        maxScrollY = maxScrollY,
        targetTop = targetRect.top,
        targetBottom = targetRect.bottom,
        marginPx = marginPx,
    )

    if (targetScrollY != null) {
        smoothScrollTo(0, targetScrollY)
    }
}

/**
 * ScrollView 콘텐츠 좌표계를 기준으로 대상 뷰가 가려져 있을 때 최소 스크롤 Y 목표값을 순수 함수로 계산합니다.
 *
 * @return 이동이 필요한 경우 목표 scrollY (`0..maxScrollY`), 이미 완전히 보이면 `null`.
 */
fun computeTargetScrollY(
    scrollY: Int,
    scrollViewHeight: Int,
    paddingTop: Int,
    paddingBottom: Int,
    maxScrollY: Int,
    targetTop: Int,
    targetBottom: Int,
    marginPx: Int = 0,
): Int? {
    val visibleHeight = scrollViewHeight - paddingTop - paddingBottom
    if (visibleHeight <= 0) return null

    val visibleTop = scrollY + paddingTop
    val visibleBottom = scrollY + scrollViewHeight - paddingBottom

    val targetHeight = targetBottom - targetTop

    // 대상이 보이는 유효 뷰포트 영역보다 큰 경우: 상단 기준 + 여백으로 정렬하여 일관되게 표시
    if (targetHeight >= visibleHeight) {
        val desiredScrollY = targetTop - paddingTop - marginPx
        val clampedScrollY = desiredScrollY.coerceIn(0, maxScrollY)
        return if (clampedScrollY != scrollY) clampedScrollY else null
    }

    // 대상이 뷰포트보다 작은 경우: 위쪽 또는 아래쪽 영역 벗어남 확인
    val isAbove = targetTop - marginPx < visibleTop
    val isBelow = targetBottom + marginPx > visibleBottom

    if (!isAbove && !isBelow) {
        // 이미 여백 포함 100% 완전 노출 상태
        return null
    }

    val desiredScrollY = when {
        isAbove -> targetTop - paddingTop - marginPx
        else -> targetBottom + paddingBottom + marginPx - scrollViewHeight
    }

    val clampedScrollY = desiredScrollY.coerceIn(0, maxScrollY)
    return if (clampedScrollY != scrollY) clampedScrollY else null
}
