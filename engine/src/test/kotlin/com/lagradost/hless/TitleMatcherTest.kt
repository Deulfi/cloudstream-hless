package com.lagradost.hless

import com.lagradost.macos.models.DetailsDto
import com.lagradost.macos.models.EpisodeDto
import com.lagradost.macos.models.SearchResultDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TitleMatcherTest {
    private fun result(name: String, type: String = "Movie", year: Int? = null) =
        SearchResultDto(name = name, url = "https://example.org/page", provider = "p", type = type, year = year)

    @Test
    fun `exact match beats fuzzy match`() {
        val info = TitleInfo(listOf("Inception"), 2010)
        val picked = pickMatch(info, "movie", listOf(result("Inceptionn", year = 2010), result("Inception (2010)", year = 2010)))
        assertEquals("Inception (2010)", picked?.name)
    }

    @Test
    fun `fuzzy match above threshold is accepted`() {
        val info = TitleInfo(listOf("The Shawshank Redemption"), 1994)
        val picked = pickMatch(info, "movie", listOf(result("Shawshank Redemption", year = 1994)))
        assertEquals("Shawshank Redemption", picked?.name)
    }

    @Test
    fun `fuzzy match below threshold is discarded`() {
        val info = TitleInfo(listOf("Inception"), 2010)
        assertNull(pickMatch(info, "movie", listOf(result("Incepti", year = 2010))))
    }

    @Test
    fun `remake years apart lose to exact year`() {
        val info = TitleInfo(listOf("Inception"), 2010)
        val picked = pickMatch(info, "movie", listOf(result("Inception", year = 2020), result("Inception", year = 2010)))
        assertEquals(2010, picked?.year)
    }

    @Test
    fun `wrong type is discarded`() {
        val info = TitleInfo(listOf("Inception"), 2010)
        assertNull(pickMatch(info, "movie", listOf(result("Inception", "TvSeries", 2010))))
    }

    @Test
    fun `year off by one still matches`() {
        val info = TitleInfo(listOf("Inception"), 2010)
        val picked = pickMatch(info, "movie", listOf(result("Inception", year = 2011)))
        assertEquals(2011, picked?.year)
    }

    private fun details(eps: List<EpisodeDto>) =
        DetailsDto(provider = "p", url = "https://example.org/page", name = "S", type = "TvSeries", episodes = eps)

    private fun ep(data: String, season: Int? = null, episode: Int? = null, audio: String? = null) =
        EpisodeDto(data = data, season = season, episode = episode, audio = audio)

    @Test
    fun `sub audio is preferred for exact season and episode`() {
        val d = details(listOf(ep("dub", 2, 3, "dub"), ep("sub", 2, 3, "sub"), ep("none", 2, 3)))
        assertEquals("sub", pickEpisode(d, 2, 3)?.data)
    }

    @Test
    fun `seasonless list falls back for season one`() {
        val d = details(listOf(ep("e1", episode = 1), ep("e2", episode = 2)))
        assertEquals("e2", pickEpisode(d, 1, 2)?.data)
    }

    @Test
    fun `missing season is null`() {
        val d = details(listOf(ep("e1", 1, 1)))
        assertNull(pickEpisode(d, 2, 1))
    }

    @Test
    fun `no audio is preferred over dub when no sub exists`() {
        val d = details(listOf(ep("dub", 1, 1, "dub"), ep("none", 1, 1)))
        assertEquals("none", pickEpisode(d, 1, 1)?.data)
    }
}
