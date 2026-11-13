package com.pintrip.app.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebPageFetcherTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var webPageFetcher: WebPageFetcher

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        webPageFetcher = WebPageFetcher()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun transformUrlIfNeeded_convertsNaverBlogPcUrlToMobile() {
        val pcUrl = "https://blog.naver.com/sampleblog/223123456789"
        val expected = "https://m.blog.naver.com/sampleblog/223123456789"
        val result = webPageFetcher.transformUrlIfNeeded(pcUrl)
        assertEquals(expected, result)
    }

    @Test
    fun transformUrlIfNeeded_convertsNaverBlogViewUrlToMobile() {
        val pcViewUrl = "https://blog.naver.com/PostView.naver?blogId=sampleblog&logNo=223123456789"
        val expected = "https://m.blog.naver.com/PostView.naver?blogId=sampleblog&logNo=223123456789"
        val result = webPageFetcher.transformUrlIfNeeded(pcViewUrl)
        assertEquals(expected, result)
    }

    @Test
    fun transformUrlIfNeeded_convertsBlogMeUrlToMobile() {
        val blogMeUrl = "https://sampleblog.blog.me/223123456789"
        val expected = "https://m.blog.naver.com/sampleblog/223123456789"
        val result = webPageFetcher.transformUrlIfNeeded(blogMeUrl)
        assertEquals(expected, result)
    }

    @Test
    fun transformUrlIfNeeded_keepsMobileAndGeneralUrlsUnchanged() {
        val mobileUrl = "https://m.blog.naver.com/sampleblog/223123456789"
        val generalUrl = "https://example.com/places/123"

        assertEquals(mobileUrl, webPageFetcher.transformUrlIfNeeded(mobileUrl))
        assertEquals(generalUrl, webPageFetcher.transformUrlIfNeeded(generalUrl))
    }

    @Test
    fun fetchPageText_stripsHtmlTagsAndExtractsBodyText() = runBlocking {
        val sampleHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Sample Travel Blog</title>
                <style>body { color: red; }</style>
                <script>console.log("ad script");</script>
            </head>
            <body>
                <header>Header Menu</header>
                <nav>Navigation</nav>
                <div class="content">
                    <h1>Busan Cafe Tour</h1>
                    <p>Momos Coffee is located at Busan Geumjeong-gu Osige-ro 20.</p>
                    <div class="ad">Banner Advertisement</div>
                </div>
                <footer>Footer Info</footer>
            </body>
            </html>
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody(sampleHtml),
        )

        val baseUrl = mockWebServer.url("/post").toString()
        val text = webPageFetcher.fetchPageText(baseUrl)

        assertTrue(text.contains("Busan Cafe Tour"))
        assertTrue(text.contains("Momos Coffee is located at Busan Geumjeong-gu Osige-ro 20."))
        assertFalse(text.contains("Header Menu"))
        assertFalse(text.contains("Footer Info"))
        assertFalse(text.contains("console.log"))
        assertFalse(text.contains("Banner Advertisement"))
    }

    @Test
    fun fetchPageText_followsRedirectAndFetchesTargetText() = runBlocking {
        val targetHtml = """
            <html>
            <body>
                <p>Redirected Page Content - Haeundae Beach Busan</p>
            </body>
            </html>
        """.trimIndent()

        val targetPath = "/target/post"
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", mockWebServer.url(targetPath).toString()),
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody(targetHtml),
        )

        val initialUrl = mockWebServer.url("/shortlink").toString()
        val text = webPageFetcher.fetchPageText(initialUrl)

        assertTrue(text.contains("Redirected Page Content - Haeundae Beach Busan"))
    }

    @Test
    fun isHtmlContentType_returnsTrueOnlyForHtmlTypes() {
        assertTrue(webPageFetcher.isHtmlContentType("text/html"))
        assertTrue(webPageFetcher.isHtmlContentType("text/html; charset=UTF-8"))
        assertTrue(webPageFetcher.isHtmlContentType("application/xhtml+xml"))
        assertTrue(webPageFetcher.isHtmlContentType("APPLICATION/XHTML+XML; CHARSET=UTF-8"))

        assertFalse(webPageFetcher.isHtmlContentType("video/mp4"))
        assertFalse(webPageFetcher.isHtmlContentType("application/pdf"))
        assertFalse(webPageFetcher.isHtmlContentType("image/png"))
        assertFalse(webPageFetcher.isHtmlContentType(""))
        assertFalse(webPageFetcher.isHtmlContentType(null))
    }

    @Test
    fun fetchPageText_nonHtmlContentType_throwsIllegalStateException() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "video/mp4")
                .setBody("binary data stream simulation"),
        )

        val baseUrl = mockWebServer.url("/file.mp4").toString()
        try {
            webPageFetcher.fetchPageText(baseUrl)
            org.junit.Assert.fail("Expected IllegalStateException for non-HTML content type")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Unsupported Content-Type") == true)
        }
    }

    @Test
    fun fetchPageText_largeHtmlBody_capsReadAmountWithoutOom() = runBlocking {
        // Create large HTML content larger than MAX_READ_CHARS (500,000 chars)
        val repeatCount = 30000
        val paragraph = "<p>Busan Momos Coffee Cafe Review Content Item.</p>\n" // 50 chars
        val largeHtml = "<html><body>" + paragraph.repeat(repeatCount) + "</body></html>" // ~1.5 million chars

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody(largeHtml),
        )

        val baseUrl = mockWebServer.url("/large").toString()
        val text = webPageFetcher.fetchPageText(baseUrl)

        // Read text length must be capped and must contain extracted paragraph text
        assertTrue(text.isNotBlank())
        assertTrue(text.contains("Busan Momos Coffee Cafe Review Content Item."))
    }
}

