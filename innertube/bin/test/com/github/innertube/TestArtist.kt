package com.github.innertube

import com.github.innertube.requests.trending
import com.github.innertube.requests.relatedPage
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TestArtist {
    @Test
    fun testTrending() = runBlocking {
        println("Fetching Trending...")
        val trendingResult = Innertube.trending()
        println("Trending isSuccess: ${trendingResult?.isSuccess}, size: ${trendingResult?.getOrNull()?.size}")
        if (trendingResult?.isFailure == true) {
            println("Trending error: ${trendingResult.exceptionOrNull()}")
        }
    }

    @Test
    fun testSearchSongs() = runBlocking {
        println("Fetching SearchSongs('Top Songs')...")
        val searchResult = com.github.innertube.requests.searchSongs("Top Songs")
        println("SearchSongs isSuccess: ${searchResult?.isSuccess}, size: ${searchResult?.getOrNull()?.items?.size}")
    }
}
