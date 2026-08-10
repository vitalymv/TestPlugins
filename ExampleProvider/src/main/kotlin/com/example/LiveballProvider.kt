package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

@CloudstreamPlugin
class LiveballPlugin : Plugin() {
    override fun load() {
        registerMainAPI(LiveballProvider())
    }
}

class LiveballProvider : MainAPI() {
    override var mainUrl = "https://liveball.sx"
    override var name = "Liveball"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "uk"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val matches = document.select("a[href*='/match/']").mapNotNull { element ->
            toSearchResult(element)
        }.distinctBy { it.url }

        return newHomePageResponse(
            HomePageList("Прямі трансляції", matches),
            hasNext = false
        )
    }

    private fun toSearchResult(element: Element): LiveSearchResponse? {
        val href = element.attr("href")
        val title = element.text().trim()
        if (title.length < 3) return null

        val url = fixUrl(href)
        return newLiveSearchResponse(title, url, TvType.Live)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.select("h1").text().ifEmpty { "Матч Liveball" }

        return newLiveStreamLoadResponse(title, url, url) {
            this.plot = "Пряма трансляція події з Liveball"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mainResponse = app.get(data)
        val mainDocument = mainResponse.document
        val iframes = mainDocument.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }

        // Посилання для перевірки WebView (спочатку iframe, якщо є, або сама сторінка)
        val targetUrl = iframes.firstOrNull()?.let { fixUrl(it) } ?: data
        val m3u8Regex = """https?://[^\s"'<>]+?\.m3u8(?:\?[^\s"'<>]*)?""".toRegex()

        var foundUrl: String? = null

        // Використовуємо WebViewResolver для перехоплення мережевого запиту .m3u8
        val webView = WebViewResolver(m3u8Regex)
        val response = app.get(
            targetUrl,
            interceptor = webView,
            headers = mapOf("Referer" to "$mainUrl/")
        )

        // Перевіряємо перехоплене посилання з WebView
        val interceptedUrl = webView.getMatch()
        if (interceptedUrl != null) {
            foundUrl = interceptedUrl
        } else {
            // Резервний пошук у згенерованому HTML після виконання JS
            val html = response.text
            foundUrl = m3u8Regex.find(html)?.value
        }

        if (foundUrl != null) {
            callback(
                newExtractorLink(
                    source = "Liveball CDN",
                    name = "Liveball Stream",
                    url = foundUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Origin" to mainUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0 Safari/537.36"
                    )
                }
            )
            return true
        }

        return false
    }
}
