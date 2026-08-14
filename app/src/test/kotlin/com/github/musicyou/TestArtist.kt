package com.github.musicyou

import com.github.innertube.Innertube
import com.github.innertube.requests.artistPage
import com.github.innertube.requests.search
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("Fetching artist page for Pritam...")
    val search = Innertube.search("Pritam")?.getOrNull()
    val pritamId = search?.artists?.firstOrNull()?.key ?: "UC7XW-FqEosSik0zstF3xKcA"
    println("Pritam ID: $pritamId")
    val artistPage = Innertube.artistPage(pritamId)
    val data = artistPage?.getOrNull()
    println("Artist page thumbnail URL: " + data?.thumbnail?.url)
}
