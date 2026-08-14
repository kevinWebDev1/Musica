package com.github.innertube

import com.github.innertube.requests.artistPage
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TestArtist {
    @Test
    fun testPritam() = runBlocking {
        println("Fetching Pritam...")
        // UCG0hRkR9sXEqdkt6Ff-G80w is A.R. Rahman maybe? Let's just use UC...
        val pritamId = "UC7XW-FqEosSik0zstF3xKcA" 
        val artistPage = Innertube.artistPage(pritamId)
        val data = artistPage?.getOrNull()
        println("Artist page thumbnail URL: " + data?.thumbnail?.url)
        println("Artist Name: " + data?.name)
    }
}
