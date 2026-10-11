package com.shilapi.xcertplay.mfi

import com.shilapi.xcertplay.iap2.message.Iap2AuthenticationMessages
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * RemoteMfiAuthenticationClient 的单元测试。
 *
 * 原实现使用 JDK 的 com.sun.net.httpserver（jdk.httpserver 模块），在 Android 单元测试的
 * android.jar 编译环境下无法解析该模块，因此改用线程级 ServerSocket 搭建极简 HTTP 桩服务器，
 * 行为与原 HttpServer 等价。作者：TRAE AI
 */
class RemoteMfiAuthenticationClientTest {
    /** 极简 HTTP 桩服务器：每个连接一个线程，按路径路由到已注册的 handler。 */
    private class StubHttpServer {
        /** 处理结果：HTTP 状态码与响应体（UTF-8）。 */
        class Result(val status: Int, val body: String)

        // 显式绑定回环 IPv4，避免 macOS 上默认通配 IPv6 绑定无法接受 127.0.0.1 连接
        private val serverSocket = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        private val handlers = ConcurrentHashMap<String, (String, String, Map<String, String>) -> Result>()
        @JvmField @Volatile var running: Boolean = true

        val port: Int
            get() = serverSocket.localPort

        fun start() {
            val thread = Thread({ acceptLoop() }, "stub-http-server")
            thread.isDaemon = true
            thread.start()
        }

        fun stop() {
            running = false
            runCatching { serverSocket.close() }
        }

        fun handler(path: String, block: (String, String, Map<String, String>) -> Result) {
            handlers[path] = block
        }

        private fun acceptLoop() {
            while (running) {
                val socket = try {
                    serverSocket.accept()
                } catch (error: Exception) {
                    break
                }
                val worker = Thread({ handle(socket) }, "stub-http-worker")
                worker.isDaemon = true
                worker.start()
            }
        }

        private fun handle(socket: java.net.Socket) {
            try {
                socket.use { connection ->
                    val input = connection.getInputStream()
                    val request = readRequest(input) ?: return@use
                    val handler = handlers[request.path]
                    val result = handler?.invoke(request.method, request.body, request.headers)
                        ?: Result(404, "{\"detail\":\"no handler\"}")
                    writeResponse(connection, result)
                }
            } catch (_: Exception) {
                // 客户端可能在响应写入前已断开（例如读超时重试），忽略即可。
            }
        }

        private fun readRequest(input: java.io.InputStream): Request? {
            val requestLine = readLine(input) ?: return null
            val parts = requestLine.split(" ")
            if (parts.size < 2) return null
            val method = parts[0]
            val path = parts[1]
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
            }
            val body = headers["content-length"]?.toIntOrNull()?.let { length ->
                val bytes = ByteArray(length)
                var read = 0
                while (read < length) {
                    val count = input.read(bytes, read, length - read)
                    if (count < 0) break
                    read += count
                }
                String(bytes, 0, read, StandardCharsets.UTF_8)
            } ?: ""
            return Request(method, path, headers, body)
        }

        private fun readLine(input: java.io.InputStream): String? {
            val builder = StringBuilder()
            while (true) {
                val value = input.read()
                if (value < 0) return if (builder.isEmpty()) null else builder.toString()
                if (value == '\n'.code) return builder.toString().trimEnd('\r')
                builder.append(value.toChar())
            }
        }

        private fun writeResponse(socket: java.net.Socket, result: Result) {
            val bytes = result.body.toByteArray(StandardCharsets.UTF_8)
            val reason = if (result.status == 200) "OK" else "Error"
            val head = "HTTP/1.1 ${result.status} $reason\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            runCatching {
                val output = socket.getOutputStream()
                output.write(head.toByteArray(StandardCharsets.UTF_8))
                output.write(bytes)
                output.flush()
            }
        }

        private class Request(
            val method: String,
            val path: String,
            val headers: Map<String, String>,
            val body: String,
        )
    }

    private lateinit var server: StubHttpServer

    @Before
    fun startServer() {
        server = StubHttpServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.stop()
    }

    @Test
    fun resetsLoadsAndCachesCertificateThenSignsWithStableRequestId() {
        val certificate = ByteArray(300) { it.toByte() }
        val signature = ByteArray(64) { (0xa0 + it).toByte() }
        val certificateRequests = AtomicInteger()
        val signRequests = Collections.synchronizedList(mutableListOf<String>())

        server.handler("/mfi/reset") { method, body, headers ->
            assertEquals("POST", method)
            assertEquals("Bearer test-token", headers["authorization"])
            assertEquals("{}", body)
            StubHttpServer.Result(200, "{\"detail\":\"\"}")
        }
        server.handler("/mfi/certificate") { _, _, _ ->
            certificateRequests.incrementAndGet()
            val encoded = Base64.getEncoder().encodeToString(certificate)
            val digest = MessageDigest.getInstance("SHA-256").digest(certificate).toHex()
            StubHttpServer.Result(
                200,
                "{\"protocolMajor\":3,\"certificate\":\"$encoded\"," +
                    "\"certificateSha256\":\"$digest\"}",
            )
        }
        server.handler("/mfi/sign") { _, body, _ ->
            signRequests += body
            if (signRequests.size == 1) {
                Thread.sleep(200)
            }
            StubHttpServer.Result(
                200,
                "{\"signature\":\"${Base64.getEncoder().encodeToString(signature)}\"}",
            )
        }

        val client = RemoteMfiAuthenticationClient(
            serverAddress = serverAddress(),
            token = "test-token",
            readTimeoutMillis = 50,
        )
        client.reset()
        assertEquals(3, client.protocolMajor())
        assertArrayEquals(certificate, client.readCertificate())
        assertEquals(1, certificateRequests.get())

        val challenge = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(signature, client.signChallenge(challenge))
        assertEquals(2, signRequests.size)
        val firstRequestId = RemoteMfiJson.string(signRequests[0], "requestId")
        val secondRequestId = RemoteMfiJson.string(signRequests[1], "requestId")
        assertEquals(firstRequestId, secondRequestId)
        assertEquals(
            Base64.getEncoder().encodeToString(challenge),
            RemoteMfiJson.string(signRequests[0], "challenge"),
        )
    }

    @Test
    fun rejectsCertificateWhenSha256DoesNotMatch() {
        server.handler("/mfi/certificate") { _, _, _ ->
            StubHttpServer.Result(
                200,
                "{\"protocolMajor\":3,\"certificate\":\"AQID\"," +
                    "\"certificateSha256\":\"${"00".repeat(32)}\"}",
            )
        }

        val error = assertThrows(MfiInvalidDataException::class.java) {
            RemoteMfiAuthenticationClient(serverAddress()).readCertificate()
        }
        assertEquals(
            "Remote certificate SHA-256 does not match certificateSha256",
            error.message,
        )
    }

    @Test
    fun loadsBaaPackageAndBuildsShowcaseIap2Payload() {
        val leaf = ByteArray(40) { (0x10 + it).toByte() }
        val intermediate = ByteArray(60) { (0x80 + it).toByte() }
        val packageBytes = u32(leaf.size) + u32(intermediate.size) + leaf + intermediate

        server.handler("/mfi/certificate") { _, _, _ ->
            val encoded = Base64.getEncoder().encodeToString(packageBytes)
            val digest = MessageDigest.getInstance("SHA-256").digest(packageBytes).toHex()
            StubHttpServer.Result(
                200,
                "{\"type\":\"baa\",\"protocolMajor\":3,\"certificate\":\"$encoded\"," +
                    "\"certificateSha256\":\"$digest\"}",
            )
        }

        val client = RemoteMfiAuthenticationClient(serverAddress())
        assertEquals(MfiCertificateType.BAA, client.certificateType)
        assertArrayEquals(
            Iap2AuthenticationMessages.baaCertificatePackage(leaf, intermediate).payload,
            client.readCertificate(),
        )
        val certificates = client.baaCertificates()
        assertArrayEquals(leaf, certificates.leaf)
        assertArrayEquals(intermediate, certificates.intermediate)
    }

    @Test
    fun rejectsUnknownCertificateType() {
        server.handler("/mfi/certificate") { _, _, _ ->
            StubHttpServer.Result(
                200,
                "{\"type\":\"unknown\",\"protocolMajor\":3,\"certificate\":\"AQID\"," +
                    "\"certificateSha256\":\"${"00".repeat(32)}\"}",
            )
        }

        val error = assertThrows(MfiInvalidDataException::class.java) {
            RemoteMfiAuthenticationClient(serverAddress()).readCertificate()
        }
        assertEquals("Unsupported remote MFI certificate type 'unknown'", error.message)
    }

    @Test
    fun resetSurfacesHttp500DetailWithoutRetrying() {
        val requests = AtomicInteger()
        server.handler("/mfi/reset") { _, _, _ ->
            requests.incrementAndGet()
            StubHttpServer.Result(500, "{\"detail\":\"chip reset failed\"}")
        }

        val error = assertThrows(RemoteMfiHttpException::class.java) {
            RemoteMfiAuthenticationClient(serverAddress()).reset()
        }
        assertEquals(500, error.statusCode)
        assertEquals("chip reset failed", error.detail)
        assertEquals(1, requests.get())
    }

    private fun serverAddress(): String = "http://127.0.0.1:${server.port}"

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
