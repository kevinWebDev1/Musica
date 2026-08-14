import com.github.innertube.Innertube
import com.github.innertube.requests.search
import com.github.innertube.requests.artistPage
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val search = Innertube.search("Pritam")?.getOrNull()
    val artists = search?.artists
    artists?.forEach { 
        println("Artist: " + it.info?.name + " ID: " + it.key + " Thumbnail: " + it.thumbnail?.url)
    }
    
    val artistPage = Innertube.artistPage("UC7XW-FqEosSik0zstF3xKcA")
    val data = artistPage?.getOrNull()
    println("Artist page thumbnail URL: " + data?.thumbnail?.url)
}
