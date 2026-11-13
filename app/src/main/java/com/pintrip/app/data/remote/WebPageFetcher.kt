package com.pintrip.app.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 웹페이지 URL에서 HTML을 가져와 본문 텍스트를 추출하는 클래스.
 *
 * 주요 기능:
 * 1. 단축 링크(naver.me 등) 및 HTTP/HTTPS 리다이렉트 추적
 * 2. 네이버 블로그 PC URL(blog.naver.com)을 모바일 URL(m.blog.naver.com)로 자동 변환 (iframe 문제 해결)
 * 3. Jsoup을 이용한 HTML 태그 및 불필요 요소 제거 후 가독성 있는 본문 텍스트 추출
 */
open class WebPageFetcher {

    /**
     * 지정된 [url]의 페이지 본문 텍스트를 추출하여 반환합니다.
     *
     * @param url 요청할 웹페이지 URL
     * @return 추출된 페이지 본문 텍스트
     * @throws Exception URL 접속 실패, 4xx/5xx 에러 또는 네트워크 오류 발생 시
     */
    open suspend fun fetchPageText(url: String): String = withContext(Dispatchers.IO) {
        val (finalUrl, html) = downloadHtmlWithRedirects(url)
        Log.d(TAG, "Fetched page text from final URL: $finalUrl (original: $url)")
        extractTextFromHtml(html, finalUrl)
    }

    /**
     * 리다이렉트를 추적하고, 네이버 블로그 PC 주소인 경우 모바일 주소로 변환하며 최종 HTML과 최종 URL을 반환합니다.
     */
    fun downloadHtmlWithRedirects(initialUrl: String): Pair<String, String> {
        var currentUrl = formatInitialUrl(initialUrl)
        var redirects = 0

        while (redirects < MAX_REDIRECTS) {
            currentUrl = transformUrlIfNeeded(currentUrl)

            val connection = createConnection(currentUrl)
            connection.instanceFollowRedirects = false
            connection.connect()

            val responseCode = connection.responseCode
            if (isRedirectCode(responseCode)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) {
                    break
                }
                currentUrl = resolveRelativeUrl(currentUrl, location)
                redirects++
                Log.d(TAG, "Redirect $redirects: $responseCode -> $currentUrl")
            } else if (responseCode in 200..299) {
                try {
                    val contentType = connection.contentType.orEmpty().lowercase()
                    if (!isHtmlContentType(contentType)) {
                        throw IllegalStateException("Unsupported Content-Type '$contentType' for URL: $currentUrl")
                    }

                    val charset = if (contentType.contains("charset=")) {
                        contentType.substringAfter("charset=").substringBefore(";").trim().uppercase()
                    } else {
                        "UTF-8"
                    }

                    val inputStream = connection.inputStream
                    val html = BufferedReader(InputStreamReader(inputStream, charset)).use { reader ->
                        val builder = StringBuilder()
                        val buffer = CharArray(BUFFER_SIZE)
                        var totalRead = 0
                        var readCount = reader.read(buffer)
                        while (totalRead < MAX_READ_CHARS && readCount != -1) {
                            val toAppend = minOf(readCount, MAX_READ_CHARS - totalRead)
                            builder.appendRange(buffer, 0, toAppend)
                            totalRead += toAppend
                            if (totalRead < MAX_READ_CHARS) {
                                readCount = reader.read(buffer)
                            }
                        }
                        builder.toString()
                    }
                    return Pair(currentUrl, html)
                } finally {
                    connection.disconnect()
                }
            } else {
                connection.disconnect()
                throw IllegalStateException("HTTP Request failed with status code: $responseCode for $currentUrl")
            }
        }

        throw IllegalStateException("Exceeded max redirects ($MAX_REDIRECTS) for $initialUrl")
    }

    fun resolveFinalUrl(initialUrl: String): String {
        return try {
            downloadHtmlWithRedirects(initialUrl).first
        } catch (_: Exception) {
            transformUrlIfNeeded(formatInitialUrl(initialUrl))
        }
    }

    /**
     * 네이버 블로그 PC URL(blog.naver.com, blog.me 등)을 모바일 URL(m.blog.naver.com)로 변환합니다.
     */
    fun transformUrlIfNeeded(url: String): String {
        var targetUrl = url.trim()

        // 1. blog.naver.com -> m.blog.naver.com
        if (targetUrl.contains("://blog.naver.com/")) {
            targetUrl = targetUrl.replace("://blog.naver.com/", "://m.blog.naver.com/")
        }

        // 2. https://{blogId}.blog.me/{logNo} -> https://m.blog.naver.com/{blogId}/{logNo}
        val blogMeRegex = Regex("https?://([a-zA-Z0-9_-]+)\\.blog\\.me/(.*)", RegexOption.IGNORE_CASE)
        val match = blogMeRegex.matchEntire(targetUrl)
        if (match != null) {
            val blogId = match.groupValues[1]
            val path = match.groupValues[2]
            targetUrl = "https://m.blog.naver.com/$blogId/$path"
        }

        return targetUrl
    }

    private fun formatInitialUrl(url: String): String {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "https://$trimmed"
        }
        return trimmed
    }

    private fun isRedirectCode(responseCode: Int): Boolean {
        return (responseCode == HttpURLConnection.HTTP_MOVED_PERM) ||
                (responseCode == HttpURLConnection.HTTP_MOVED_TEMP) ||
                (responseCode == HttpURLConnection.HTTP_SEE_OTHER) ||
                (responseCode == 307) ||
                (responseCode == 308)
    }

    private fun resolveRelativeUrl(baseUrl: String, location: String): String {
        return try {
            URL(URL(baseUrl), location).toString()
        } catch (_: Exception) {
            location
        }
    }

    private fun createConnection(urlString: String): HttpURLConnection {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
        return connection
    }



    fun isHtmlContentType(contentType: String?): Boolean {
        val lower = contentType.orEmpty().lowercase()
        return lower.contains("text/html") || lower.contains("application/xhtml+xml")
    }

    private fun extractTextFromHtml(html: String, baseUrl: String): String {
        val doc = Jsoup.parse(html, baseUrl)

        // HTML 내 스크립트, 스타일, 헤더, 푸터, 네비게이션, 광고/프레임 등 불필요 요소 제거
        doc.select("script, style, head, header, footer, nav, noscript, svg, iframe, .ad, .advertisement").remove()

        return doc.body().text().replace("\\s+".toRegex(), " ").trim()
    }
    companion object {
        private const val TAG = "WebPageFetcher"
        private const val MAX_REDIRECTS = 10
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 10000
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        const val MAX_READ_CHARS = 500_000
        private const val BUFFER_SIZE = 8192
    }
}
