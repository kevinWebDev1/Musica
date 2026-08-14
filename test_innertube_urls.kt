import com.github.innertube.Innertube
import com.github.innertube.requests.searchSongs
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val songs = searchSongs("Shape of you")?.getOrNull()?.items?.filterIsInstance<Innertube.SongItem>()
    songs?.take(3)?.forEach {
        println("Song Thumbnail: ${it.thumbnail?.url}")
    }
}
