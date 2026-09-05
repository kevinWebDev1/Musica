package com.github.innertube

import com.github.innertube.requests.relatedPage
import com.github.innertube.requests.trending
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

class InnertubeTest {
    @Test
    fun testTrending() = runBlocking {
        println("Testing Innertube.trending()...")
        val trendingResult = Innertube.trending()
        val songs = trendingResult?.getOrNull()
        println("Trending isSuccess: ${trendingResult?.isSuccess}, value count: ${songs?.size}")
        if (trendingResult?.isFailure == true) {
            println("Trending error: ${trendingResult.exceptionOrNull()}")
        }
        
        songs?.forEach { item ->
            if (item is Innertube.SongItem) {
                println("Song: ${item.title} - ${item.artists?.joinToString { it.name ?: "" }}")
            }
        }
    }
    
    @Test
    fun testRelatedPage() = runBlocking {
        println("\nTesting Innertube.relatedPage('fJ9rUzIMcZQ')...")
        val relatedResult = Innertube.relatedPage("fJ9rUzIMcZQ")
        println("Related isSuccess: ${relatedResult?.isSuccess}")
        val page = relatedResult?.getOrNull()
        println("Songs count: ${page?.songs?.size}")
        println("Playlists count: ${page?.playlists?.size}")
        println("Albums count: ${page?.albums?.size}")
        println("Artists count: ${page?.artists?.size}")
        if (relatedResult?.isFailure == true) {
            println("Related error: ${relatedResult.exceptionOrNull()}")
        }
    }
}

