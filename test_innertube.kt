import com.github.innertube.Innertube
import com.github.innertube.requests.trending
import com.github.innertube.requests.relatedPage
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("Testing Innertube.trending()...")
    val trendingResult = Innertube.trending()
    println("Trending isSuccess: ${trendingResult?.isSuccess}, value count: ${trendingResult?.getOrNull()?.size}")
    if (trendingResult?.isFailure == true) {
        println("Trending error: ${trendingResult.exceptionOrNull()}")
    }
    
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
