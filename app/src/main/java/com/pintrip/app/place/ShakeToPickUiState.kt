package com.pintrip.app.place

import androidx.annotation.StringRes
import com.pintrip.app.data.model.PlaceDetail

/**
 * 흔들어서 고르기 기능에서 사용하는 장소 카테고리 필터 종류.
 *
 * 카카오 API 매핑 정보 및 docs/data-contract.md §4 규격을 준수합니다:
 * - ALL: 전체 보기 ("all")
 * - DINING: 식당 ("dining")
 * - CAFE: 카페 ("cafe")
 * - SIGHTS: 관광지 ("sights")
 * - STAY: 숙소 ("stay")
 * - ETC: 기타 ("etc")
 */
enum class PlaceCategory(val code: String) {
    ALL("all"),
    DINING("dining"),
    CAFE("cafe"),
    SIGHTS("sights"),
    STAY("stay"),
    ETC("etc");

    /**
     * 장소의 매핑된 카테고리 문자열이 현재 카테고리 필터 조건에 부합하는지 확인합니다.
     */
    fun matches(placeCategory: String?): Boolean {
        if (this == ALL) return true
        val normalized = placeCategory?.lowercase()?.trim().orEmpty()
        return normalized == code
    }
}

/**
 * 추첨 후보 장소가 없는 비어있는 상태의 세부 원인.
 */
enum class EmptyReason {
    /** 방에 저장된 장소 자체가 0개임 */
    NO_SAVED_PLACES,
    /** 저장된 장소는 있으나, 카카오 재조회 결과 found 상태인 장소가 0개임 */
    NO_AVAILABLE_PLACES,
    /** found 상태 장소는 있으나, 현재 선택한 카테고리 필터에 해당하는 장소가 0개임 */
    NO_FILTER_MATCHES,
}

/**
 * 흔들어서 고르기 화면의 UI 상태.
 */
sealed class ShakeToPickUiState {
    object Idle : ShakeToPickUiState()

    /**
     * 장소 목록 재조회 로딩 중 상태.
     */
    data class Loading(
        val category: PlaceCategory = PlaceCategory.ALL,
    ) : ShakeToPickUiState()

    /**
     * 장소 로드 완료 후 추첨 대기 상태.
     *
     * @property category 현재 선택된 카테고리 필터
     * @property totalCount 방의 전체 저장 장소 수
     * @property availableCount status == STATUS_FOUND 인 장소 수
     * @property filteredCount 현재 카테고리 필터 조건에 맞는 장소 수
     */
    data class Ready(
        val category: PlaceCategory,
        val totalCount: Int,
        val availableCount: Int,
        val filteredCount: Int,
    ) : ShakeToPickUiState()

    /**
     * 추첨 결과 상태.
     *
     * @property selectedPlace 추첨된 장소 (PlaceDetail)
     * @property category 현재 선택된 카테고리 필터
     * @property totalCount 방의 전체 저장 장소 수
     * @property availableCount status == STATUS_FOUND 인 장소 수
     * @property filteredCount 현재 카테고리 필터 조건에 맞는 장소 수
     */
    data class Result(
        val selectedPlace: PlaceDetail,
        val category: PlaceCategory,
        val totalCount: Int,
        val availableCount: Int,
        val filteredCount: Int,
    ) : ShakeToPickUiState()

    /**
     * 추첨 후보 장소가 없는 비어있는 상태.
     *
     * @property reason 빈 상태 원인 (NO_SAVED_PLACES | NO_AVAILABLE_PLACES | NO_FILTER_MATCHES)
     * @property category 현재 선택된 카테고리 필터
     * @property totalCount 방의 전체 저장 장소 수
     * @property availableCount status == STATUS_FOUND 인 장소 수
     */
    data class Empty(
        val reason: EmptyReason,
        val category: PlaceCategory = PlaceCategory.ALL,
        val totalCount: Int = 0,
        val availableCount: Int = 0,
    ) : ShakeToPickUiState()

    /**
     * 장소 로드 또는 방 정보 조회 실패 상태.
     *
     * @property messageResId error string resource ID
     */
    data class Error(
        @get:StringRes val messageResId: Int,
    ) : ShakeToPickUiState()
}
