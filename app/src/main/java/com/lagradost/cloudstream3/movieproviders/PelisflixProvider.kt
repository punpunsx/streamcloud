package com.lagradost.cloudstream3.movieproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlin.collections.ArrayList

class PelisflixProvider:MainAPI() {
    override val mainUrl: String
        get() = "https://pelisflix.li"
    override val name: String
        get() = "Pelisflix"
    override val lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    override suspend fun getMainPage(): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("$mainUrl/ver-peliculas-online-gratis-fullhdc3/", "Películas"),
            Pair("$mainUrl/ver-series-online-gratis/", "Series"),
        )
        for (i in urls) {
            try {
                val soup = app.get(i.first).document
                val home = soup.select("article.TPost.B").map {
                    val title = it.selectFirst("h2.title").text()
                    val link = it.selectFirst("a").attr("href")
                    TvSeriesSearchResponse(
                        title,
                        link,
                        this.name,
                        TvType.Movie,
                        it.selectFirst("figure img").attr("data-src"),
                        null,
                        null,
                    )
                }

                items.add(HomePageList(i.second, home))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }
    override suspend fun search(query: String): ArrayList<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        val search = doc.select("article.TPost.B").map {
            val href = it.selectFirst("a").attr("href")
            val poster = it.selectFirst("figure img").attr("data-src")
            val name = it.selectFirst("h2.title").text()
            val isMovie = href.contains("/pelicula/")
            if (isMovie) {
                MovieSearchResponse(
                    name,
                    href,
                    this.name,
                    TvType.Movie,
                    poster,
                    null
                )
            } else {
                TvSeriesSearchResponse(
                    name,
                    href,
                    this.name,
                    TvType.TvSeries,
                    poster,
                    null,
                    null
                )
            }
        }
        return ArrayList(search)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, timeout = 120).document
        val title = doc.selectFirst("h1.Title").text()

        val postercss = doc.selectFirst("head").toString()
        val posterRegex = Regex("(\"og:image\" content=\"https:\\/\\/pelisflix.li\\/wp-content\\/uploads\\/(\\d+)\\/(\\d+)\\/.*.jpg)")
        val poster = try {
            posterRegex.findAll(postercss).map {
                it.value.replace("\"og:image\" content=\"","")
            }.toList().first()
        } catch (e: Exception) {
            doc.select(".TPostBg").attr("src")
        }
        val desc = if (url.contains("pelicula")) doc.selectFirst("div.Description strong").text() else doc.selectFirst("div.Description").text()
        val tags = doc.select("p.Genre a")
            .map { it?.text()?.trim().toString() }
        val seasonsDocument = app.get(url).document
        val episodes = arrayListOf<TvSeriesEpisode>()
        val year = doc.selectFirst("div.TPMvCn div.Info span.Date").text().toIntOrNull()

        seasonsDocument.select(".episodes-load")
            .forEachIndexed { season, element ->
                val seasonId = element.select("a").attr("href")
                if (seasonId.isNullOrEmpty()) return@forEachIndexed

                var episode = 0
                app.get(seasonId).document
                    .select("tbody tr.Viewed")
                    .forEach {
                        val episodeImg = it.select("img") ?: return@forEach
                        val episodeTitle = it.selectFirst(".MvTbTtl > a").text()?: return@forEach
                        val episodePosterUrl = episodeImg.attr("src") ?: return@forEach
                        val episodeData = it.selectFirst("td.MvTbTtl a").attr("href") ?: return@forEach

                        episode++

                        val epnum = it.selectFirst("tr.Viewed span.Num").text().toIntOrNull()

                        episodes.add(
                            TvSeriesEpisode(
                                episodeTitle,
                                season + 1,
                                epnum,
                                episodeData,
                                fixUrl(episodePosterUrl)
                            )
                        )
                    }
            }

        return when (val tvType = if (episodes.isEmpty() && url.contains("pelicula")) TvType.Movie else TvType.TvSeries) {
            TvType.TvSeries -> {
                TvSeriesLoadResponse(
                    title,
                    url,
                    this.name,
                    tvType,
                    episodes,
                    poster,
                    year,
                    desc,
                    ShowStatus.Ongoing,
                    null,
                    null,
                    tags,
                )
            }
            TvType.Movie -> {
                MovieLoadResponse(
                    title,
                    url,
                    this.name,
                    tvType,
                    url,
                    poster,
                    year,
                    desc,
                    null,
                    null,
                    tags,
                )
            }
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("li button.Button.sgty").apmap {
            val movieID = it.attr("data-id")
            val serverID = it.attr("data-key")
            val type = if (data.contains("pelicula")) 1 else 2
            val url = "$mainUrl/?trembed=$serverID&trid=$movieID&trtype=$type" //This is to get the POST key value
            val doc1 = app.get(url).document
            val select1 = doc1.selectFirst("div.Video iframe").attr("src")
            val postkey = select1.replace("/stream/index.php?h=","") // this obtains
        // djNIdHNCR2lKTGpnc3YwK3pyRCs3L2xkQmljSUZ4ai9ibTcza0JRODNMcmFIZ0hPejdlYW0yanJIL2prQ1JCZA POST KEY
            val server = app.post("https://pelisflix.li/stream/r.php",
                headers = mapOf("Host" to "pelisflix.li",
                    "User-Agent" to USER_AGENT,
                    "Accept" to "ext/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Content-Length" to "88",
                    "Origin" to "null",
                    "DNT" to "1",
                    "Connection" to "keep-alive",
                    "Upgrade-Insecure-Requests" to "1",
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "same-origin",
                    "Sec-Fetch-User" to "?1",
                    "Pragma" to "no-cache",
                    "Cache-Control" to "no-cache",
                    "TE" to "trailers"),
                params = mapOf(Pair("h", postkey)),
                data =  mapOf(Pair("h", postkey)),
                allowRedirects = false
            ).response.headers.values("location")
            for (link in server) {
                for (extractor in extractorApis) {
                    if (link.replace("#bu","").startsWith(extractor.mainUrl)) {
                        extractor.getSafeUrl(link, data)?.forEach {
                            callback(it)
                        }
                    }
                }
            }
        }
        return true
    }
}