import com.github.innertube.Innertube
import com.github.innertube.requests.artistPage
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("Fetching artist page for A.R. Rahman...")
    val artistPage = Innertube.artistPage("UCYvAEE_nEnu49X3mY_L5rPA")
    println("Artist success: ${artistPage?.isSuccess}")
    val data = artistPage?.getOrNull()
    println("Artist name: ${data?.name}")
    println("Artist thumbnail: ${data?.thumbnail}")
    println("Artist thumbnail URL: ${data?.thumbnail?.url}")
}
