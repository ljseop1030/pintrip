package com.pintrip.app.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Test

class KakaoLocalApiClientTest {

    @Test(expected = IllegalArgumentException::class)
    fun testSearchKeyword_blankQueryThrowsException() = runBlocking {
        val client = KakaoLocalApiClient(apiKey = "dummy_key")
        client.searchKeyword(query = "   ")
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSearchKeyword_blankApiKeyThrowsException() = runBlocking {
        val client = KakaoLocalApiClient(apiKey = "")
        client.searchKeyword(query = "모모스커피")
        Unit
    }
}
