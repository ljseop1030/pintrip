package com.pintrip.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GeminiExtractionResponseTest {

    @Test
    fun testGeminiExtractionResponseSchema_initialized() {
        assertNotNull(GEMINI_EXTRACTION_RESPONSE_SCHEMA)
        assertEquals("OBJECT", GEMINI_EXTRACTION_RESPONSE_SCHEMA.type)
    }
}
