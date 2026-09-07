package com.github.musicyou

import com.github.innertube.Innertube
import com.github.innertube.requests.artistPage
import com.github.innertube.requests.searchSongs
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("Fetching artist page for Pritam...")
    val searchResult = searchSongs("Pritam")?.getOrNull()
    val pritamId = searchResult?.items?.firstOrNull()?.authors?.firstOrNull()?.endpoint?.browseId ?: "UC7XW-FqEosSik0zstF3xKcA"
    println("Pritam ID: $pritamId")
    val artistPage = Innertube.artistPage(pritamId)
    val data = artistPage?.getOrNull()
    println("Artist page thumbnail URL: " + data?.thumbnail?.url)
}
