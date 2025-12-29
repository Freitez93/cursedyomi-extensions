package eu.kanade.tachiyomi.animeextension.es.pelisplushd

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class PelisPlusHDFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        PelisPlusHD("PelisPlusHD", "https://pelisplushd.bz"),
        // Pelisplusto("PelisPlusTo", "https://ww3.pelisplus.to"),
        // Pelisplusph("PelisPlusPh", "https://ww5.pelisplushd.pe"),
    )
}
