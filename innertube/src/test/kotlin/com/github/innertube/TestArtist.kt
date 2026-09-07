package com.github.innertube

import com.github.innertube.requests.trending
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class TestArtist {
    @Test
    fun testTrending() = runTest {
        println("Fetching Trending...")
        val trendingResult = Innertube.trending()
        println("Trending isSuccess: ${trendingResult?.isSuccess}, size: ${trendingResult?.getOrNull()?.size}")
        if (trendingResult?.isFailure == true) {
            println("Trending error: ${trendingResult.exceptionOrNull()}")
        }
    }

    @Test
    fun testSearchSongs() = runTest {
        println("Fetching SearchSongs('Top Songs')...")
        val searchResult = com.github.innertube.requests.searchSongs("Top Songs")
        println("SearchSongs isSuccess: ${searchResult?.isSuccess}, size: ${searchResult?.getOrNull()?.items?.size}")
    }
}
