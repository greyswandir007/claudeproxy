package ru.wizard.web.claudeproxy

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Мини HTTP-прокси для интеграционных тестов M31. Реактор-нетти при типе
 * HTTP всегда открывает туннель CONNECT (даже для http://-целей), поэтому
 * фейк обязан уметь: CONNECT host:port → 200 и двунаправленный мост к
 * целевому порту; прочие запросы (absolute-URI) → 204 сразу. Считает
 * установленные соединения.
 */
class FakeHttpProxy(private val targetPort: () -> Int) : Closeable {

    private val serverSocket = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
    private val acceptedConnections = AtomicInteger()
    private val acceptThread = Thread { acceptLoop() }

    val port: Int get() = serverSocket.localPort
    val connectionCount: Int get() = acceptedConnections.get()

    fun start() {
        acceptThread.isDaemon = true
        acceptThread.start()
    }

    override fun close() {
        runCatching { serverSocket.close() }
    }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val client = try {
                serverSocket.accept()
            } catch (closed: SocketException) {
                break
            }
            acceptedConnections.incrementAndGet()
            spawnThread { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val requestLine = readLine(input) ?: return
            // заголовки до пустой строки
            while (true) {
                val headerLine = readLine(input) ?: return
                if (headerLine.isEmpty()) break
            }
            if (requestLine.startsWith("CONNECT", ignoreCase = true)) {
                output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                output.flush()
                tunnel(input, output, socket)
            } else {
                output.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray())
                output.flush()
            }
        }
    }

    /** Двунаправленный мост клиент ↔ цель (цель всегда локальный targetPort). */
    private fun tunnel(input: InputStream, output: java.io.OutputStream, client: Socket) {
        Socket("127.0.0.1", targetPort()).use { upstream ->
            val upstreamInput = upstream.getInputStream()
            val upstreamOutput = upstream.getOutputStream()
            val toUpstream = Thread {
                runCatching { input.copyTo(upstreamOutput) }
                runCatching { upstream.shutdownOutput() }
            }
            val toClient = Thread {
                runCatching { upstreamInput.copyTo(output) }
                runCatching { client.shutdownOutput() }
            }
            toUpstream.isDaemon = true
            toClient.isDaemon = true
            toUpstream.start()
            toClient.start()
            toUpstream.join()
            toClient.join()
        }
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) {
                return if (builder.isEmpty()) null else builder.toString()
            }
            if (byte == '\n'.code) {
                return builder.toString().trimEnd('\r')
            }
            builder.append(byte.toChar())
        }
    }

    private fun spawnThread(block: () -> Unit): Thread =
        Thread(block).apply {
            isDaemon = true
            start()
        }
}
