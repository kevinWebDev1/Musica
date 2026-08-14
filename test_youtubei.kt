import java.net.URL
import java.net.HttpURLConnection

fun main() {
    val videoId = "inEu2qQuGZ8"
    val url = URL("https://youtubei.googleapis.com/youtubei/v1/player")
    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.setRequestProperty("Content-Type", "application/json")
    connection.doOutput = true
    
    val body = """{
        "context": {
            "client": {
                "clientName": "ANDROID",
                "clientVersion": "17.36.4"
            }
        },
        "videoId": "$videoId"
    }"""
    
    connection.outputStream.write(body.toByteArray())
    connection.connect()

    val response = connection.inputStream.bufferedReader().readText()
    if (response.contains("hlsManifestUrl")) {
        println("Has hlsManifestUrl: " + response.substringAfter("hlsManifestUrl\":\"").substringBefore("\""))
    }
    if (response.contains("dashManifestUrl")) {
        println("Has dashManifestUrl: " + response.substringAfter("dashManifestUrl\":\"").substringBefore("\""))
    }
}
