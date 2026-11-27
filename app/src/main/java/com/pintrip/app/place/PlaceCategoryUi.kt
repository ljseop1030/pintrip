package com.pintrip.app.place

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.pintrip.app.R

/**
 * 장소 카테고리를 화면에 표시할 때 쓰는 이름과 아이콘 (DESIGN.md Icons > Place categories).
 * 방 홈 장소 목록, 카테고리 칩 제목, 흔들어서 고르기에서 같은 매핑을 공유한다.
 */
@StringRes
fun PlaceCategory.labelResId(): Int = when (this) {
    PlaceCategory.ALL -> R.string.category_all
    PlaceCategory.DINING -> R.string.category_dining
    PlaceCategory.CAFE -> R.string.category_cafe
    PlaceCategory.SIGHTS -> R.string.category_sights
    PlaceCategory.STAY -> R.string.category_stay
    PlaceCategory.ETC -> R.string.category_etc
}

/** 장소 한 곳의 아이콘. ALL은 필터 전용이라 장소에 붙지 않으므로 기타와 같은 점을 쓴다. */
@DrawableRes
fun PlaceCategory.iconResId(): Int = when (this) {
    PlaceCategory.DINING -> R.drawable.ic_place_dining
    PlaceCategory.CAFE -> R.drawable.ic_place_cafe
    PlaceCategory.SIGHTS -> R.drawable.ic_place_sights
    PlaceCategory.STAY -> R.drawable.ic_place_stay
    PlaceCategory.ALL, PlaceCategory.ETC -> R.drawable.ic_place_etc
}