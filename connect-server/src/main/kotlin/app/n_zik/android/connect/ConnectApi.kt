package app.n_zik.android.connect

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class ApiCapabilities(
    val protocolVersion: Int = 1,
    val searchMediaTypes: List<String> = listOf("track"),
    val sourceTransports: List<Transport> = listOf(Transport.DIRECT, Transport.RANGE),
    val personalLibrary: Boolean = false,
    val queueControl: Boolean = false,
)

@Serializable
internal data class SourceDescriptor(
    val trackId: String,
    val transport: Transport,
    val mimeType: String,
    val url: String,
    val headers: Map<String, String>,
    val expiresAtEpochMs: Long?,
    val contentLength: Long?,
    val requireBoundedRange: Boolean,
    val rangeChunkSizeBytes: Long?,
) {
    override fun toString(): String = "SourceDescriptor(trackId=$trackId, transport=$transport)"
}

@Serializable
private data class ApiProblem(val reason: String)

internal suspend fun serveApi(music: ConnectMusicService, token: String, port: Int) {
    validateApiToken(token)
    requireConnect(port in 1..65535)
    val server = embeddedServer(CIO, host = "127.0.0.1", port = port) { connectApi(music, token) }
    try {
        connectLogger.info("Starting loopback API on port=$port")
        server.startSuspend(wait = true)
    } finally {
        withContext(NonCancellable) { server.stopSuspend(1_000, 5_000) }
    }
}

internal fun Application.connectApi(
    music: ConnectMusicService,
    token: String,
    clock: () -> Long = System::currentTimeMillis,
    requestTimeoutMs: Long = 90_000,
) {
    validateApiToken(token)
    requireConnect(requestTimeoutMs > 0)
    val expectedToken = token.toByteArray(StandardCharsets.UTF_8)
    install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
    routing {
        get("/v1/capabilities") {
            call.apiReply(expectedToken, requestTimeoutMs) { ApiCapabilities() }
        }
        get("/v1/search") {
            call.apiReply(expectedToken, requestTimeoutMs) {
                val query = call.request.queryParameters["q"].orEmpty().trim()
                validateQuery(query)
                val rawLimit = call.request.queryParameters["limit"]
                val limit = if (rawLimit == null) 20 else rawLimit.toIntOrNull()
                    ?: throw ConnectFailure(FailureCode.INVALID_ARGUMENT)
                requireConnect(limit in 1..50)
                music.search(query).take(limit)
            }
        }
        get("/v1/tracks/{id}") {
            call.apiReply(expectedToken, requestTimeoutMs) {
                val id = call.parameters["id"].orEmpty()
                validateTrackId(id)
                music.metadata(id)
            }
        }
        get("/v1/tracks/{id}/source") {
            call.apiReply(expectedToken, requestTimeoutMs) {
                val id = call.parameters["id"].orEmpty()
                validateTrackId(id)
                var source = music.resolve(id, allowSabr = false)
                if (source.expiresAtEpochMs?.let { it <= clock() + 5_000 } == true) {
                    music.refresh()
                    source = music.resolve(id, allowSabr = false)
                }
                if (source.expiresAtEpochMs?.let { it <= clock() + 5_000 } == true) {
                    throw ConnectFailure(FailureCode.SOURCE_REJECTED)
                }
                if (source.transport == Transport.SABR) throw ConnectFailure(FailureCode.UNSUPPORTED_TRANSPORT)
                if (source.id != id) throw ConnectFailure(FailureCode.INVALID_MEDIA_RESPONSE)
                val url = source.url ?: throw ConnectFailure(FailureCode.INVALID_MEDIA_RESPONSE)
                validateMediaUrl(url)
                if (source.transport == Transport.RANGE && source.rangeChunkSize <= 0) {
                    throw ConnectFailure(FailureCode.INVALID_MEDIA_RESPONSE)
                }
                SourceDescriptor(
                    trackId = id,
                    transport = source.transport,
                    mimeType = source.mimeType,
                    url = url,
                    headers = source.headers,
                    expiresAtEpochMs = source.expiresAtEpochMs,
                    contentLength = source.contentLength,
                    requireBoundedRange = source.transport == Transport.RANGE,
                    rangeChunkSizeBytes = source.rangeChunkSize.takeIf { source.transport == Transport.RANGE },
                )
            }
        }
    }
}

internal fun validateApiToken(token: String) {
    requireConnect(token.length in 32..256 && token.all { it.code in 33..126 })
}

private suspend inline fun <reified T : Any> ApplicationCall.apiReply(
    expectedToken: ByteArray,
    requestTimeoutMs: Long,
    crossinline block: suspend () -> T,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    val authorization = request.headers[HttpHeaders.Authorization].orEmpty()
    val provided = authorization.removePrefix("Bearer ")
    if (!authorization.startsWith("Bearer ") || provided.length !in 32..256 ||
        !MessageDigest.isEqual(expectedToken, provided.toByteArray(StandardCharsets.UTF_8))
    ) {
        response.header(HttpHeaders.WWWAuthenticate, "Bearer")
        respond(HttpStatusCode.Unauthorized, ApiProblem("unauthorized"))
        return
    }
    try {
        respond(withTimeout(requestTimeoutMs) { block() })
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        respond(HttpStatusCode.GatewayTimeout, ApiProblem(FailureCode.TIMEOUT.name))
    } catch (error: CancellationException) {
        throw error
    } catch (error: ConnectFailure) {
        val status = when (error.code) {
            FailureCode.INVALID_ARGUMENT -> HttpStatusCode.BadRequest
            FailureCode.UNSUPPORTED_TRANSPORT -> HttpStatusCode.NotImplemented
            FailureCode.SOURCE_REJECTED -> HttpStatusCode.ServiceUnavailable
            FailureCode.TIMEOUT -> HttpStatusCode.GatewayTimeout
            else -> HttpStatusCode.BadGateway
        }
        respond(status, ApiProblem(error.code.name))
    } catch (error: Exception) {
        connectLogger.warning("API request failed: ${error.javaClass.simpleName}")
        respond(HttpStatusCode.BadGateway, ApiProblem(FailureCode.UNEXPECTED_FAILURE.name))
    }
}
