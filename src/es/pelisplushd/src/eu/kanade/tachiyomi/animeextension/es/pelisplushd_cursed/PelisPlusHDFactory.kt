package eu.kanade.tachiyomi.animeextension.es.pelisplushd_cursed

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class PelisPlusHDFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        // changed name to avoid collision with another repo
        PelisPlusHD("PelisPlusHD+", "https://pelisplushd.bz"),
        // Pelisplusto("PelisPlusTo", "https://ww3.pelisplus.to"),
        // Pelisplusph("PelisPlusPh", "https://ww5.pelisplushd.pe"),
    )
}
