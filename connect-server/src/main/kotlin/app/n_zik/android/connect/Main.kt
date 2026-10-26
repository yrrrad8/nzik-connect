package app.n_zik.android.connect

import java.util.logging.ConsoleHandler
import java.util.logging.Formatter
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.system.exitProcess

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val connectLogger: Logger = Logger.getLogger("NzikConnect").apply {
    useParentHandlers = false
    addHandler(ConsoleHandler().apply {
        formatter = object : Formatter() {
            override fun format(record: LogRecord): String = "[NzikConnect] ${record.message}${System.lineSeparator()}"
        }
    })
}

private val json = Json { encodeDefaults = true }

@Serializable
private data class FailureReport(val status: String = "fail", val reason: FailureCode, val exceptionTypes: List<String> = emptyList())

/** Runs standalone search, metadata and decoded-audio smoke commands. */
suspend fun main(args: Array<String>) {
    if (args.isEmpty() || args.contentEquals(arrayOf("--help"))) {
        connectLogger.info("Usage: connect-server search --query TEXT | track --id VIDEO_ID | smoke --query TEXT [--seconds 10] [--max-bytes 8] [--deadline 180] [--ffmpeg PATH] | serve [--port 8765] (requires NZIK_CONNECT_TOKEN)")
        return
    }
    try {
        execute(args)
    } catch (_: TimeoutCancellationException) {
        fail(FailureCode.TIMEOUT)
    } catch (error: CancellationException) {
        throw error
    } catch (error: ConnectFailure) {
        fail(error.code)
    } catch (error: Exception) {
        fail(FailureCode.UNEXPECTED_FAILURE, error)
    }
}

private suspend fun execute(args: Array<String>) {
    val command = args.first()
    requireConnect(command in setOf("search", "track", "smoke", "serve"))
    val values = parseOptions(args.drop(1))
    val allowed = when (command) {
        "search" -> setOf("--query")
        "track" -> setOf("--id")
        "serve" -> setOf("--port")
        else -> setOf("--query", "--seconds", "--max-bytes", "--deadline", "--ffmpeg")
    }
    requireConnect(values.keys.all { it in allowed })
    val query = values["--query"].orEmpty().trim()
    val id = values["--id"].orEmpty()
    if (command == "track") validateTrackId(id)
    if (command == "search" || command == "smoke") validateQuery(query)
    val token = if (command == "serve") System.getenv("NZIK_CONNECT_TOKEN").orEmpty().also(::validateApiToken) else ""
    val port = values.number("--port", 8765)
    requireConnect(port in 1..65535)
    val smoke = if (command == "smoke") SmokeOptions(
        query = query,
        seconds = values.number("--seconds", 10),
        maxBytes = values.number("--max-bytes", 8).toLong() * 1024 * 1024,
        deadlineMs = values.number("--deadline", 180).toLong() * 1_000,
        ffmpeg = values["--ffmpeg"] ?: "ffmpeg",
    ) else null
    val music = InnerTubeMusicService()
    try {
        when (command) {
            "search" -> connectLogger.info(json.encodeToString(music.search(query)))
            "track" -> connectLogger.info(json.encodeToString(music.metadata(id)))
            "serve" -> serveApi(music, token, port)
            "smoke" -> {
                HttpClient(OkHttp) {
                    followRedirects = false
                    install(HttpTimeout) {
                        connectTimeoutMillis = 10_000
                        requestTimeoutMillis = 45_000
                        socketTimeoutMillis = 20_000
                    }
                }.use { http ->
                    val report = SmokeRunner(music, StreamConsumer(KtorDirectFetcher(http)), PcmVerifier())
                        .run(requireNotNull(smoke))
                    connectLogger.info(json.encodeToString(report))
                }
            }
        }
    } finally {
        try {
            music.close()
        } catch (_: Exception) {
            connectLogger.fine("Music resources did not close normally")
        }
    }
}

internal fun parseOptions(args: List<String>): Map<String, String> {
    requireConnect(args.size % 2 == 0)
    val values = linkedMapOf<String, String>()
    args.chunked(2).forEach { (key, value) ->
        requireConnect(key.startsWith("--") && key !in values && value.isNotBlank())
        values[key] = value
    }
    return values
}

private fun Map<String, String>.number(key: String, default: Int): Int =
    if (key in this) this[key]?.toIntOrNull() ?: throw ConnectFailure(FailureCode.INVALID_ARGUMENT) else default

private fun fail(code: FailureCode, error: Exception? = null): Nothing {
    val types = generateSequence<Throwable>(error) { it.cause }.take(4).map { it.javaClass.simpleName }.toList()
    connectLogger.severe(json.encodeToString(FailureReport(reason = code, exceptionTypes = types)))
    exitProcess(1)
}
