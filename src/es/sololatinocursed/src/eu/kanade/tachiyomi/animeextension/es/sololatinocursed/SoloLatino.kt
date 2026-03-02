package eu.kanade.tachiyomi.animeextension.es.sololatinocursed

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelFlatMapBlocking
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

// Extensión para SoloLatino, hereda del multisrc DooPlay para reusar lógicas estándar
open class SoloLatino : DooPlay(
    "es",
    "SoloLatino+",
    "https://sololatino.net",
) {

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "article.item"
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/tendencias/page/$page", headers)
    override fun popularAnimeNextPageSelector(): String = "div.pagMovidy a"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val img = element.selectFirst("img")
            val url = element.selectFirst("a")?.attr("href") ?: element.attr("href")

            setUrlWithoutDomain(url)
            title = img?.attr("alt") ?: ""
            // Si data-srcset no existe, hacemos un fallback seguro al atributo src
            thumbnail_url = img?.attr("data-srcset") ?: img?.attr("src")
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/pelicula/estrenos/page/$page", headers)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()
    override fun latestUpdatesSelector() = popularAnimeSelector()

    // ============================== Search ================================
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = SoloLatinoFilters.getSearchParameters(filters)

        val path = when {
            params.genre.isNotBlank() -> {
                when (params.genre) {
                    "animes" -> "/genres_animes"
                    "peliculas" -> "/genres"
                    "series" -> "/genres_series"
                    "tendencias", "ratings", "genre_series/toons" -> "/${params.genre}"
                    else -> "/genres/${params.genre}"
                }
            }
            params.platform.isNotBlank() -> "/network/${params.platform}"
            params.year.isNotBlank() -> "/year/${params.year}"
            else -> buildString {
                append(if (query.isNotBlank()) "/?s=$query" else "/")
                append(
                    when (params.type) {
                        "serie" -> "series"
                        "pelicula" -> "peliculas"
                        "anime" -> "animes"
                        "toon" -> "genre_series/toons"
                        "todos" -> ""
                        else -> "tendencias"
                    },
                )
                if (params.isInverted) append("&orden=asc")
            }
        }

        return if (path.startsWith("/?s=")) {
            GET("$baseUrl/page/$page$path", headers)
        } else {
            GET("$baseUrl$path/page/$page", headers)
        }
    }

    // =========================== Anime Details ============================
    override val additionalInfoSelector = "#single > div.content > div.wp-content"

    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document) // Resuelve de DooPlay
        val url = doc.selectFirst("meta[property='og:url']")?.attr("content") ?: ""
        val sheader = doc.selectFirst("div.sheader")

        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())

            // Acceso seguro para extraer la imagen y el título del DOM
            sheader?.selectFirst("div.poster > img")?.let { imgElement ->
                thumbnail_url = imgElement.getImageUrl()
                title = imgElement.attr("alt").ifEmpty {
                    sheader.selectFirst("div.data > h1")?.text() ?: ""
                }
            }

            author = doc.selectFirst(".person .data .name a")?.text()
            genre = sheader?.select("div.sgeneros > a")?.joinToString { it.text() }
            status = if (url.contains("/peliculas/")) SAnime.COMPLETED else SAnime.UNKNOWN

            doc.selectFirst(additionalInfoSelector)?.let { info ->
                description = buildString {
                    append(doc.getDescription())
                    additionalInfoItems.forEach {
                        info.getInfo(it)?.let { infoText -> append("\n$infoText") }
                    }
                }
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val seasonList = doc.select("div#seasons div.se-c")

        return if (seasonList.isEmpty()) {
            // Manejo como película con único "episodio"
            listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(doc.location())
                    episode_number = 1F
                    name = episodeMovieText
                    date_upload = doc.selectFirst("span.date")?.text()?.toDate() ?: 0L
                },
            )
        } else {
            // Iterar sobre las temporadas y unificar en una sola lista (inversa)
            seasonList.flatMap(::getSeasonEpisodes).reversed()
        }
    }

    override fun getSeasonEpisodes(season: Element): List<SEpisode> {
        val seasonName = season.attr("data-season")

        return season.select("ul.episodios li").mapNotNull { element ->
            runCatching {
                episodeFromElement(element, seasonName)
            }.onFailure { it.printStackTrace() }.getOrNull()
        }
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException("Not used.")

    override fun episodeFromElement(element: Element, seasonName: String): SEpisode {
        return SEpisode.create().apply {
            val epNum = element.selectFirst("div.numerando")?.text()
                ?.trim()
                ?.let { episodeNumberRegex.find(it)?.groupValues?.last() } ?: "0"

            val href = element.selectFirst("a[href]")?.attr("href") ?: ""
            val episodeName = element.selectFirst("div.epst")?.text()?.let { text ->
                if ("Episodio " in text) "Sin título" else text
            } ?: "Sin título"

            episode_number = epNum.toFloatOrNull() ?: 0F
            date_upload = element.selectFirst("span.date")?.text()?.toDate() ?: 0L

            name = "T$seasonName - Episodio $epNum: $episodeName"
            setUrlWithoutDomain(href)
        }
    }

    // ============================ Video Links =============================
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val mp4UploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val wolfStreamExtractor by lazy { WolfstreamExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val dataPost = Regex("""data-type=["'](.+?)["'] data-post=["'](.+?)["'] data-nume=["'](.+?)["']""")
            .findAll(document.html())
            .toList()

        // Decodificación inicial de iframes haciendo un post recursible, safe-catch al interior de processLinkPage.
        val iframeList = dataPost.mapNotNull { processLinkPage(it, response.request.url.toString()) }

        // Mapeo en paralelo asíncrono para evaluar los links finales.
        return iframeList.parallelFlatMapBlocking { url ->
            when {
                url.contains("embed69") -> {
                    Embed69(client).getLinks(url).flatMap { (language, links) ->
                        serverVideoResolver(links, " $language")
                    }
                }
                url.contains("re.sololatino.net") -> {
                    ReEmbed(client).getLinks(url).flatMap { (language, links) ->
                        serverVideoResolver(links, " $language")
                    }
                }
                else -> emptyList()
            }
        }
    }

    private fun processLinkPage(mR: MatchResult, path: String): String? {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", "${mR.groups[2]?.value}")
            .add("nume", "${mR.groups[3]?.value}")
            .add("type", "${mR.groups[1]?.value}")
            .build()

        val newHeaders = headers.newBuilder()
            .set("Referer", path)
            .add("Accept", "*/*")
            .build()

        return runCatching {
            val responseHtml = client.newCall(
                POST(
                    url = "$baseUrl/wp-admin/admin-ajax.php",
                    headers = newHeaders,
                    body = body,
                ),
            ).execute().body.string()

            Log.d("SoloLatino", "processLinkPage: $responseHtml")
            // Rescata el enlace final mediante Función auxiliar Regex (getFirstMatch se sitúa en Extractor.kt)
            getFirstMatch("""<iframe class='[^']+' src='([^']+)""", responseHtml)
        }.onFailure {
            Log.e("SoloLatino", "Error in processLinkPage: ${it.message}")
        }.getOrNull()
    }

    private fun serverVideoResolver(urls: List<String>, prefix: String = ""): List<Video> {
        val domVidHide = listOf("dintezuvio", "filelions", "vidhide", "anime7u")
        val domFileMoon = listOf("filemoon", "moonplayer", "bysedikamoum")
        val domStreamWish = listOf("streamwish", "wish", "hglink", "hgplaycdn", "iplayerhls")

        return urls.parallelFlatMapBlocking { url ->
            runCatching {
                Log.d("SoloLatino", "Resolviendo URL: $url")
                when {
                    "voe" in url -> voeExtractor.videosFromUrl(url, "$prefix ")
                    "uqload" in url -> uqloadExtractor.videosFromUrl(url, prefix)
                    "mp4upload" in url -> mp4UploadExtractor.videosFromUrl(url, headers, "$prefix ")
                    "wolfstream" in url -> wolfStreamExtractor.videosFromUrl(url, "$prefix ")
                    domFileMoon.any { url.contains(it, ignoreCase = true) } -> filemoonExtractor.videosFromUrl(url, "$prefix Filemoon:")
                    domVidHide.any { url.contains(it, ignoreCase = true) } -> {
                        val mediaID = url.substringAfterLast("/")
                        vidHideExtractor.videosFromUrl("https://callistanise.com/v/$mediaID", videoNameGen = { "$prefix VidHide:$it" })
                    }
                    domStreamWish.any { url.contains(it, ignoreCase = true) } -> streamWishExtractor.videosFromUrl(url, "$prefix StreamWish")
                    else -> universalExtractor.videosFromUrl(url, headers, prefix = "$prefix ")
                }
            }.getOrDefault(emptyList()) // Manejo seguro de fallos
        }
    }

    // ============================== Filters ===============================
    override val fetchGenres = false
    override fun getFilterList() = SoloLatinoFilters.FILTER_LIST

    // ============================= Preferences ============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Añade selector primario de "Quality preference"

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Servidor Preferido"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                if (index != -1) {
                    val entry = entryValues[index] as String
                    preferences.edit().putString(key, entry).commit()
                } else {
                    true
                }
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_ENTRIES
            entryValues = PREF_LANG_VALUES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                if (index != -1) {
                    val entry = entryValues[index] as String
                    preferences.edit().putString(key, entry).commit()
                } else {
                    true
                }
            }
        }.also(screen::addPreference)
    }

    // Listado exportable para calidad
    override val prefQualityValues = arrayOf("1080", "720", "480", "360")
    override val prefQualityEntries = prefQualityValues

    companion object {
        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Idioma Preferido"
        private const val PREF_LANG_DEFAULT = "[LAT]"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"

        // Elementos divididos apropiadamente en lugar de un string aglomerado
        private val SERVER_LIST = arrayOf("StreamWish", "Uqload", "StreamHideVid", "Mp4Upload", "Voe", "VidHide")

        private val PREF_LANG_ENTRIES = arrayOf("[LAT]", "[SUB]", "[CAST]")
        private val PREF_LANG_VALUES = arrayOf("[LAT]", "[SUB]", "[CAST]")
    }

    // ============================= Utilities ==============================
    override fun String.toDate(): Long {
        return runCatching {
            val dateFormat = SimpleDateFormat("MMM. dd, yyyy", Locale.ENGLISH)
            dateFormat.parse(this)?.time ?: 0L
        }.getOrDefault(0L)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, prefQualityDefault) ?: prefQualityDefault
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

        // Corrección importante de bug: se usaba por error PREF_LANG_TITLE como key para las preferencias
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT) ?: PREF_LANG_DEFAULT

        return sortedWith(
            compareBy(
                { it.quality.contains(lang, ignoreCase = true) },
                { it.quality.contains(server, ignoreCase = true) },
                { it.quality.contains(quality) },
                // Extraer orden de numeración de la resolución
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }
}
