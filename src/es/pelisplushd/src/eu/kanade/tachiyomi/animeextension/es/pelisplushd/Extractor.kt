package eu.kanade.tachiyomi.animeextension.es.pelisplushd

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.jsoup.Jsoup

class Embed69(private val client: OkHttpClient) {
    fun getLinks(url: String): Map<String, List<String>> {
        val mainUrl = "https://embed69.org"
        val res = client.newCall(GET(url)).execute().asJsoup()
        val jsonString = res.selectFirst("script:containsData(dataLink)")?.data()
            ?.substringAfter("dataLink = ")
            ?.substringBefore(";")

        val allLinksByLanguage = mutableMapOf<String, MutableList<String>>()
        if (jsonString != null) {
            try {
                val jsonArray = JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val fileObject = jsonArray.getJSONObject(i)
                    val language = fileObject.getString("video_language")
                    val embeds = fileObject.getJSONArray("sortedEmbeds")
                    val serverLinks = mutableListOf<String>()
                    for (j in 0 until embeds.length()) {
                        val embedObj = embeds.getJSONObject(j)
                        embedObj.optString("link").let { link ->
                            if (link.isNotBlank()) serverLinks.add("\"$link\"")
                        }
                    }
                    val json = """ {"links":$serverLinks} """.trimIndent()
                        .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                    val decrypted = client.newCall(
                        POST(
                            "$mainUrl/api/decrypt",
                            body = json,
                        ),
                    ).execute().parseAs<Loadlinks>()

                    Log.d("PelisPlusHD", decrypted.toString())
                    if (decrypted.success) {
                        val links = decrypted.links.map { it.link }
                        val listForLang = allLinksByLanguage.getOrPut(language) { mutableListOf() }
                        listForLang.addAll(links)
                    }
                }
            } catch (e: Exception) {
                // Handle error appropriately
                Log.e("PelisPlusHD", "Error loading links: ${e.message}")
            }
        } else {
            Log.d("PelisPlusHD", "dataLink not found in response")
        }
        return allLinksByLanguage
    }
}

class ReEmbed(private val client: OkHttpClient) {
    fun getLinks(url: String): Map<String, List<String>> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val mapUrl = mapOf(
            ".OD_LAT > li" to "LAT",
            ".OD_SUB > li" to "SUB",
        )
        val links = mutableMapOf<String, List<String>>()

        mapUrl.forEach { (selector, language) ->
            val langLinks = mutableListOf<String>()
            document.select(selector).forEach { link ->
                runCatching {
                    val onclickAttr = link.attr("onclick")
                    getFirstMatch("""['"](https?:\/\/[^'"]+)['"]""", onclickAttr)?.let { langLinks.add(it) }
                    getFirstMatch("""\.php\?link=(.+?)&servidor=""", onclickAttr)?.let {
                        langLinks.add(String(Base64.decode(it, Base64.DEFAULT)))
                    }
                }.onFailure {
                    Log.e("PelisPlusHD", "Error al procesar enlace antiguo: ${it.message}")
                }
            }
            if (langLinks.isNotEmpty()) {
                links[language] = langLinks
            }
        }
        return links
    }
}

class WolfstreamExtractor(private val client: OkHttpClient) {
    // Mark the function as suspend
    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        // Use withContext to switch to a background thread (Dispatchers.IO)
        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(GET(url)).execute()

                if (!response.isSuccessful) {
                    // Handle non-successful responses
                    return@withContext emptyList()
                }

                val document = Jsoup.parse(response.body.string())
                val mediaUrl = document.selectFirst("script:containsData(sources)")?.data()
                    ?.substringAfter("{file:\"")
                    ?.substringBefore("\"")
                    ?: return@withContext emptyList()

                listOf(Video(mediaUrl, "${prefix}WolfStream", mediaUrl))
            } catch (e: Exception) {
                // Catch potential exceptions (e.g., network errors, parsing issues)
                // You might want to log the exception here: Log.e("WolfstreamExtractor", "Error fetching videos", e)
                Log.e("PelisPusHD", "Error fetching videos", e)
                emptyList()
            }
        }
    }
}

// ================================ Funciones Auxiliares ================================
fun getFirstMatch(pattern: String, input: String): String? {
    return pattern.toRegex().find(input)?.groupValues?.get(1)
}

// ===================================== data class =====================================
@Serializable
data class Loadlinks(
    val success: Boolean,
    val links: List<Link>,
) {
    @Serializable
    data class Link(
        val index: Long,
        val link: String,
    )
}
