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
        val mainHtml = mainResponse.text

        val m3u8Regex = """https?://[^\s"'<>]+?\.m3u8(?:\?[^\s"'<>]*)?""".toRegex()
        val fileRegex = """file\s*:\s*["']([^"']+)["']""".toRegex()
        val srcRegex = """src\s*:\s*["']([^"']+)["']""".toRegex()

        fun extractAndAdd(text: String): Boolean {
            val directStream = m3u8Regex.find(text)?.value
            if (directStream != null) {
                callback(
                    newExtractorLink(
                        source = "Liveball",
                        name = "Liveball Stream",
                        url = directStream,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0 Safari/537.36"
                        )
                    }
                )
                return true
            }
            return false
        }

        // 1. Пошук прямо на головній сторінці
        if (extractAndAdd(mainHtml)) return true

        // 2. Отримання та перевірка всіх iframe на сторінці
        val iframes = mainDocument.select("iframe").mapNotNull { 
            val src = it.attr("src").ifBlank { it.attr("data-src") }
            if (src.isNotBlank()) fixUrl(src) else null
        }

        for (iframeUrl in iframes) {
            try {
                val iframeResponse = app.get(
                    iframeUrl,
                    headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0 Safari/537.36"
                    )
                )
                val iframeHtml = iframeResponse.text

                if (extractAndAdd(iframeHtml)) return true

                // Пошук зашифрованих або відносних посилань у JS-пасивних параметрах file/src
                val match = fileRegex.find(iframeHtml) ?: srcRegex.find(iframeHtml)
                if (match != null) {
                    val streamCandidate = match.groupValues[1]
                    if (extractAndAdd(streamCandidate)) return true
                }
            } catch (e: Exception) {
                // Пропускаємо недоступні фрейми
            }
        }

        return false
    }
}
