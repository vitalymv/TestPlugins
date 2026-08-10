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

        // Універсальна регулярка для m3u8
        val universalM3u8Regex = """https?://[^\s"'<>]+?\.m3u8(?:\?[^\s"'<>]*)?""".toRegex()

        // 1. Пошук прямо у коді сторінки матчу
        val directMatch = universalM3u8Regex.find(mainHtml)
        if (directMatch != null) {
            addLink(directMatch.value, callback)
            return true
        }

        // 2. Пошук у всіх iframe
        val iframes = mainDocument.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }

        for (iframeSrc in iframes) {
            val iframeUrl = fixUrl(iframeSrc)

            try {
                val iframeResponse = app.get(
                    iframeUrl,
                    headers = mapOf("Referer" to "$mainUrl/")
                )
                val iframeHtml = iframeResponse.text
                val iframeDoc = iframeResponse.document

                // Шукаємо у першому iframe
                val match = universalM3u8Regex.find(iframeHtml)
                if (match != null) {
                    addLink(match.value, callback)
                    return true
                }

                // Перевірка вкладених iframe (другий рівень)
                val nestedIframes = iframeDoc.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }
                for (nestedSrc in nestedIframes) {
                    val nestedUrl = fixUrlNull(nestedSrc) ?: continue
                    try {
                        val nestedHtml = app.get(
                            nestedUrl,
                            headers = mapOf("Referer" to iframeUrl)
                        ).text

                        val nestedMatch = universalM3u8Regex.find(nestedHtml)
                        if (nestedMatch != null) {
                            addLink(nestedMatch.value, callback)
                            return true
                        }
                    } catch (e: Exception) {
                        // Ігноруємо помилки
                    }
                }

            } catch (e: Exception) {
                // Ігноруємо помилки
            }
        }
        return false
    }

    // Додано suspend для сумісності з новим SDK
    private suspend fun addLink(streamUrl: String, callback: (ExtractorLink) -> Unit) {
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
                    "Origin" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0 Safari/537.36"
                )
            }
        )
    }
}
