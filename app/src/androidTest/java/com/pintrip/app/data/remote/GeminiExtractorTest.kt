package com.pintrip.app.data.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeminiExtractorTest {

    @Test
    fun testGeminiExtractorInitialization() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val extractor = GeminiExtractor(context)
        assertNotNull(extractor)
        assertEquals("gemini-3.6-flash", GeminiExtractor.MODEL_NAME)
        assertEquals(1080, GeminiExtractor.MAX_IMAGE_WIDTH)
    }
}
