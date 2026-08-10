package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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

        // Передаємо лише name, url, dataUrl та блок налаштування
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
        val mainDocument = app.get(data).document
        val iframes = mainDocument.select("iframe").map { it.attr("src") }

        for (iframeSrc in iframes) {
            if (iframeSrc.isBlank()) continue
            val iframeUrl = fixUrl(iframeSrc)

            try {
                val iframeHtml = app.get(
                    iframeUrl, 
                    headers = mapOf("Referer" to "$mainUrl/")
                ).text

                val m3u8Regex = """https?://[^\s"'<>]+/hls/[^\s"'<>]+chunks\.m3u8\?[^\s"'<>]+""".toRegex()
                val match = m3u8Regex.find(iframeHtml)

                if (match != null) {
                    val streamUrl = match.value

                    callback(
                        newExtractorLink(
                            source = "Liveball CDN",
                            name = "Liveball HLS",
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "Origin" to "http://liveball.sx",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0 Safari/537.36"
                            )
                        }
                    )
                    return true
                }
            } catch (e: Exception) {
                // Ігноруємо помилки мережі/парсингу
            }
        }
        return false
    }
}
