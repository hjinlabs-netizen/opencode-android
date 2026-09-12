package com.anomalyco.opencode.util

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Minimal REAL-socket HTTP/1.1 SSE test server for the production Ktor
 * client path (Sprint 1c spike). Speaks just enough of the protocol to
 * exercise behaviours MockWebServer cannot script deterministically:
 *
 *  - handshake flush BEFORE any body chunk (`beginSse` + hold),
 *  - comment-only keep-alive frames,
 *  - CLEAN chunked termination (`finish`) vs
 *  - ABRUPT mid-stream death (`kill`) to force real read exceptions,
 *  - plain non-SSE responses (SPA html fallbacks, 401s) for probe
 *    classification, with the request sequence recorded for assertions.
 *
 * Deliberately dumb: no connection reuse, no keep-alive on OUR side; the
 * client opens one socket per request, which is exactly how Ktor's SSE
 * sessions behave. Lives in `src/sharedTest/java` — one source of truth
 * compiled into BOTH the JVM integration tier (1c.2) and the androidTest
 * device tier (1c.3).
 */
class SseTestServer : AutoCloseable {

    data class Request(val method: String, val path: String, val headers: Map<String, String>) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    val requests = CopyOnWriteArrayList<Request>()
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val baseUrl: String = "http://127.0.0.1:${server.localPort}"
    private val executor = Executors.newCachedThreadPool()
    private val openSockets = CopyOnWriteArrayList<Socket>()

    /** Handler installed per scenario; receives the parsed request + connection. */
    @Volatile
    var handler: (Request, Connection) -> Unit = { _, conn -> conn.respond(404, "text/plain", "not found") }

    /** Signalled once per request AFTER the handler returned (stream ended or died). */
    @Volatile
    var served: CountDownLatch = CountDownLatch(0)

    init {
        val acceptThread = Thread {
            runCatching {
                while (!server.isClosed) {
                    val socket = server.accept()
                    openSockets += socket
                    executor.execute { serve(socket) }
                }
            }
        }
        acceptThread.isDaemon = true
        acceptThread.start()
    }

    fun expectRequests(count: Int) {
        served = CountDownLatch(count)
    }

    private fun serve(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val headers = buildMap {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) put(line.substring(0, separator).lowercase(), line.substring(separator + 1).trim())
                }
            }
            val request = Request(parts[0], parts[1], headers)
            requests += request
            handler(request, Connection(socket))
        } catch (_: Exception) {
            // Client hung up; irrelevant for the assertions.
        } finally {
            runCatching { socket.close() }
            openSockets.remove(socket)
            served.countDown()
        }
    }

    /** One request's writable side. */
    inner class Connection(private val socket: Socket) {
        private val out = socket.getOutputStream()

        fun respond(status: Int, contentType: String, body: String) {
            write(
                "HTTP/1.1 $status ${reason(status)}\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n" +
                    body,
            )
            out.flush()
        }

        /** Headers-only SSE start: the client handshake completes on flush. */
        fun beginSse() {
            write(
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/event-stream\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: keep-alive\r\n" +
                    "Transfer-Encoding: chunked\r\n\r\n",
            )
            out.flush()
        }

        /** One `data:` event. */
        fun event(data: String) = chunk("data: $data\n\n")

        /** One comment/keep-alive line (carries no `data`, client must filter it). */
        fun comment(text: String) = chunk(": $text\n\n")

        /** Clean end: terminating zero-chunk, then close. */
        fun finish() {
            write("0\r\n\r\n")
            out.flush()
            runCatching { socket.close() }
        }

        /** Mid-stream death: close without the terminating chunk. */
        fun kill() {
            socket.setSoLinger(true, 0) // RST — the client sees a real I/O error
            runCatching { socket.close() }
        }

        private fun chunk(payload: String) {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            write(Integer.toHexString(bytes.size) + "\r\n")
            out.write(bytes)
            write("\r\n")
            out.flush()
        }

        private fun write(text: String) = out.write(text.toByteArray(Charsets.UTF_8))

        private fun reason(status: Int) = when (status) {
            200 -> "OK"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Status"
        }
    }

    override fun close() {
        openSockets.forEach { runCatching { it.setSoLinger(true, 0); it.close() } }
        runCatching { server.close() }
        executor.shutdownNow()
    }
}
