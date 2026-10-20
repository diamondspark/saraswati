package com.metrolist.innertube

import com.metrolist.innertube.saraswati.LocalIdEgressGuard
import com.metrolist.innertube.saraswati.LocalSongEgressException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalIdEgressGuardTest {
    @Serializable
    private data class Body(val videoId: String)

    private fun client(engine: MockEngine) =
        HttpClient(engine) {
            install(LocalIdEgressGuard)
            install(ContentNegotiation) { json() }
        }

    private fun ok() = MockEngine { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) }

    @Test
    fun `a JSON body naming local 42 never reaches the engine and throws LocalSongEgressException`() =
        runBlocking {
            val engine = ok()
            client(engine).use { client ->
                assertThrows(LocalSongEgressException::class.java) {
                    runBlocking {
                        client.post("https://music.youtube.com/youtubei/v1/next") {
                            contentType(ContentType.Application.Json)
                            setBody(Body("local:42"))
                        }
                    }
                }
            }
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun `a URL containing an encoded local colon 42 is refused`() =
        runBlocking {
            val engine = ok()
            client(engine).use { client ->
                assertThrows(LocalSongEgressException::class.java) {
                    runBlocking { client.get("https://music.youtube.com/watch?v=local%3A42") }
                }
                assertThrows(LocalSongEgressException::class.java) {
                    runBlocking { client.get("https://music.youtube.com/watch?v=LOCAL:7") }
                }
            }
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun `an ordinary next request passes unchanged`() =
        runBlocking {
            val engine = ok()
            client(engine).use { client ->
                client.post("https://music.youtube.com/youtubei/v1/next") {
                    contentType(ContentType.Application.Json)
                    setBody(Body("dQw4w9WgXcQ"))
                }
            }
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun `a search for the word local passes`() =
        runBlocking {
            val engine = ok()
            client(engine).use { client ->
                client.post("https://music.youtube.com/youtubei/v1/search") {
                    contentType(ContentType.Application.Json)
                    setBody(Body("local: songs near me"))
                }
                client.get("https://music.youtube.com/search?q=local+songs")
            }
            assertEquals(2, engine.requestHistory.size)
        }
}
