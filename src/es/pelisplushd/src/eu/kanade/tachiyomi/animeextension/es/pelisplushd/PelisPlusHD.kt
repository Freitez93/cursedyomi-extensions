package eu.kanade.tachiyomi.animeextension.es.pelisplushd

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelFlatMapBlocking
import extensions.utils.getPreferencesLazy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

open class PelisPlusHD(override val name: String, override val baseUrl: String) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val id: Long = 1400819034564144238L
    override val lang = "es"
    override val supportsLatest = true
    val preferences by getPreferencesLazy()
    private val json: Json by injectLazy()

    companion object {
        const val PREF_QUALITY_KEY = "preferred_quality"
        const val PREF_QUALITY_DEFAULT = "1080"
        val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf("StreamWish", "Uqload", "StreamHideVid", "Mp4Upload", "Voe, VidHide, StreamWish")
        private val REGEX_VIDEO_OPTS = "'(https?://[^']*)'".toRegex()
    }

    // ==================================== PopularPage ===================================== //
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/peliculas/populares?page=$page")
    override fun popularAnimeNextPageSelector(): String = "a.page-link"
    override fun popularAnimeSelector(): String = "div.Posters a.Posters-link"
    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.select("a").attr("abs:href"))
            title = element.select("a div.listing-content p").text().substringBeforeLast(" (")
            thumbnail_url = element.select("a img").attr("src").replace("/w154/", "/w200/")
        }
    }

    // ===================================== LatestPage ===================================== //
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/year/2025?page=$page")
    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()
    override fun latestUpdatesSelector(): String = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    // ==================================== AnimeSearch ===================================== //
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val tagFilter = filters.find { it is Tags } as Tags

        return when {
            query.isNotBlank() -> GET("$baseUrl/search?s=$query&page=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}?page=$page")
            tagFilter.state.isNotBlank() -> GET("$baseUrl/year/${tagFilter.state}?page=$page")
            else -> GET("$baseUrl/peliculas?page=$page")
        }
    }
    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()
    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    // ==================================== AnimeDetails ==================================== //
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst("h1.m-b-5")!!.text().substringBeforeLast(" (")
            thumbnail_url = document.selectFirst("meta[property='og:image']")!!
                .attr("content")
            description = document.selectFirst("div.col-sm-4 div.text-large")!!
                .ownText()
            genre = document.select("div.p-v-20.p-h-15.text-center a span").joinToString {
                it.text()
            }
            status = SAnime.COMPLETED
        }
    }
    override fun episodeListSelector() = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val jsoup = response.asJsoup()
        if (response.request.url.toString().contains("/pelicula/")) {
            val episode = SEpisode.create().apply {
                episode_number = 1F
                name = "PELÍCULA"
                setUrlWithoutDomain(response.request.url.toString())
            }
            episodes.add(episode)
        } else {
            jsoup.select("div.tab-content div a").forEachIndexed { index, element ->
                val episode = SEpisode.create().apply {
                    episode_number = (index + 1).toFloat()
                    name = element.text()
                    setUrlWithoutDomain(element.attr("abs:href"))
                }
                episodes.add(episode)
            }
        }
        return episodes.reversed()
    }

    // ===================================== VideoList ====================================== //
    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val data = document.selectFirst("script:containsData(video[1] = )")?.data() ?: return emptyList()
        val iframeList = REGEX_VIDEO_OPTS.findAll(data).toList().map {
            it.groupValues.get(1)
        }

        Log.d("PelisPlusHD", "videoListParse: $iframeList")
        return iframeList.flatMap { url ->
            when {
                url.contains("embed69") -> {
                    val languageToLinks = Embed69(client).getLinks(url)
                    languageToLinks.flatMap { (language, links) ->
                        serverVideoResolver(links, " $language")
                    }
                }
                url.contains("re.sololatino.net") -> {
                    val languageToLinks = ReEmbed(client).getLinks(url)
                    languageToLinks.flatMap { (language, links) ->
                        serverVideoResolver(links, " $language")
                    }
                }
                else -> emptyList()
            }
        }
    }

    // =================================== VideoExtractors ================================== //
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val mp4UploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val wolfStreamExtractor by lazy { WolfstreamExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }

    private fun serverVideoResolver(urls: List<String>, prefix: String = ""): List<Video> {
        val domVidHide = arrayOf("dintezuvio", "filelions", "vidhide", "anime7u")
        val domFileMoon = arrayOf("filemoon", "moonplayer", "bysedikamoum")
        val domStreamWish = arrayOf("streamwish", "wish", "hglink", "hgplaycdn", "iplayerhls")

        return urls.parallelFlatMapBlocking { url ->
            runCatching {
                Log.d("SoloLatino", "URL: $url")
                when {
                    "voe" in url -> voeExtractor.videosFromUrl(url, "$prefix ")
                    "uqload" in url -> uqloadExtractor.videosFromUrl(url, prefix)
                    "mp4upload" in url -> mp4UploadExtractor.videosFromUrl(url, headers, "$prefix ")
                    "wolfstream" in url -> wolfStreamExtractor.videosFromUrl(url, "$prefix ")
                    domFileMoon.any(url) -> filemoonExtractor.videosFromUrl(url, "$prefix Filemoon:")
                    domVidHide.any(url) -> {
                        val mediaID = url.substringAfterLast("/")
                        vidHideExtractor.videosFromUrl("https://callistanise.com/v/$mediaID", videoNameGen = { "$prefix VidHide:$it" })
                    }
                    domStreamWish.any(url) -> streamWishExtractor.videosFromUrl(url, "$prefix StreamWish")
                    else -> universalExtractor.videosFromUrl(url, headers, prefix = "$prefix ")
                }
            }.getOrElse { emptyList() }
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro de año"),
        GenreFilter(),
        AnimeFilter.Header("Busqueda por año"),
        Tags("Año"),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<selecionar>", ""),
            Pair("Peliculas", "peliculas"),
            Pair("Series", "series"),
            Pair("Doramas", "generos/dorama"),
            Pair("Animes", "animes"),
            Pair("Acción", "generos/accion"),
            Pair("Animación", "generos/animacion"),
            Pair("Aventura", "generos/aventura"),
            Pair("Ciencia Ficción", "generos/ciencia-ficcion"),
            Pair("Comedia", "generos/comedia"),
            Pair("Crimen", "generos/crimen"),
            Pair("Documental", "generos/documental"),
            Pair("Drama", "generos/drama"),
            Pair("Fantasía", "generos/fantasia"),
            Pair("Foreign", "generos/foreign"),
            Pair("Guerra", "generos/guerra"),
            Pair("Historia", "generos/historia"),
            Pair("Misterio", "generos/misterio"),
            Pair("Pelicula de Televisión", "generos/pelicula-de-la-television"),
            Pair("Romance", "generos/romance"),
            Pair("Suspense", "generos/suspense"),
            Pair("Terror", "generos/terror"),
            Pair("Western", "generos/western"),
        ),
    )

    private class Tags(name: String) : AnimeFilter.Text(name)

    open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private fun Array<String>.any(url: String): Boolean = this.any { url.contains(it, ignoreCase = true) }

    infix fun <A, B> Pair<A, B>.to(c: String): Triple<A, B, String> = Triple(this.first, this.second, c)

    @Serializable
    data class DataLinkDto(
        @SerialName("video_language")
        val videoLanguage: String? = null,
        @SerialName("sortedEmbeds")
        val sortedEmbeds: List<SortedEmbedsDto?> = emptyList(),
    )

    @Serializable
    data class SortedEmbedsDto(
        val link: String? = null,
        val type: String? = null,
        val servername: String? = null,
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
