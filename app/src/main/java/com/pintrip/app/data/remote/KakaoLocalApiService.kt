package com.pintrip.app.data.remote

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * 카카오 Local REST API Retrofit 인터페이스.
 */
interface KakaoLocalApiService {

    /**
     * 카카오 Local 키워드 검색 API (`GET /v2/local/search/keyword.json`).
     */
    @GET("v2/local/search/keyword.json")
    suspend fun searchKeyword(
        @Header("Authorization") authorization: String,
        @Query("query") query: String,
        @Query("category_group_code") categoryGroupCode: String? = null,
        @Query("page") page: Int? = null,
        @Query("size") size: Int? = null,
        @Query("x") x: String? = null,
        @Query("y") y: String? = null,
        @Query("radius") radius: Int? = null,
        @Query("rect") rect: String? = null,
        @Query("sort") sort: String? = null,
    ): Response<String>
}
