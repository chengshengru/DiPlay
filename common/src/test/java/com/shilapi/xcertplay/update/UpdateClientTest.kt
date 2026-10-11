package com.shilapi.xcertplay.update

// main #488：UpdateClient 的 HTTP 集成测试。
// 分支适配：AGP 7.4.2 下 :common 单测编译解析不到 jdk.httpserver 模块（com.sun.net.httpserver），
// 改用裸 ServerSocket 手写 HTTP 桩，覆盖场景与 main 一致（作者：TRAE AI）。
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // 手写 HTTP 桩：每个请求按路由规则应答（作者：TRAE AI）
    private var server: ServerSocket? = null
    private lateinit var root: String

    /** 路由应答规则：路径 -> (状态码, 响应体)。body 为 null 时模拟不完整下载（声明 1024 只写 16 字节）。 */
    private val routes = mutableMapOf<String, Pair<Int, ByteArray?>>()

    @Before
    fun startServer() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        root = "http://${server!!.inetAddress.hostAddress}:${server!!.localPort}"
        Thread({
            while (!server!!.isClosed) {
                val socket = try {
                    server!!.accept()
                } catch (_: Exception) {
                    return@Thread
                }
                serve(socket)
            }
        }, "update-client-test-stub").apply { isDaemon = true }.start()
    }

    private fun serve(socket: Socket) {
        socket.use { connection ->
            val request = connection.getInputStream()
            val path = request.readLine()?.split(" ")?.getOrNull(1) ?: return
            // 读掉请求头，避免连接残留影响下一个请求
            while (request.readLine()?.isNotEmpty() == true) { /* 丢弃 */ }
            val (status, body) = routes[path] ?: (200 to "[]".toByteArray())
            val output = connection.getOutputStream()
            if (body == null) {
                // 截断应答：声明 Content-Length 大于实际写入，模拟不完整下载
                output.write("HTTP/1.1 $status OK\r\nContent-Length: 1024\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(ByteArray(16))
                output.flush()
                return
            }
            output.write(
                "HTTP/1.1 $status OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(),
            )
            output.write(body)
            output.flush()
        }
    }

    @After
    fun stopServer() {
        server?.close()
    }

    @Test
    fun fetchTextReturnsTheBody() {
        val body = "[{\"tag_name\": \"v0.2.14\"}]"
        routes["/releases"] = 200 to body.toByteArray()
        assertEquals(body, UpdateClient.fetchText("$root/releases", "application/vnd.github+json", "DiPlay/test"))
    }

    @Test
    fun fetchTextUsesTheSuppliedNetworkConnectionFactory() {
        routes["/selected-network"] = 200 to "[]".toByteArray()
        val selected = AtomicBoolean()

        UpdateClient.fetchText("$root/selected-network", null, "DiPlay/test") { url ->
            selected.set(true)
            url.openConnection()
        }

        assertTrue(selected.get())
    }

    @Test
    fun fetchTextFailsOnHttpErrors() {
        routes["/forbidden"] = 403 to ByteArray(0)
        try {
            UpdateClient.fetchText("$root/forbidden", null, "DiPlay/test")
            throw AssertionError("expected an IOException")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("403"))
        }
    }

    @Test
    fun downloadWritesTheFileAndReportsProgress() {
        val payload = ByteArray(200 * 1024) { (it % 251).toByte() }
        routes["/apk"] = 200 to payload
        val destination = File(temporaryFolder.root, "update/DiPlay.apk")
        val reports = mutableListOf<Pair<Long, Long?>>()
        UpdateClient.download("$root/apk", destination) { written, total -> reports.add(written to total) }
        assertEquals(payload.toList(), destination.readBytes().toList())
        assertEquals(payload.size.toLong(), reports.last().first)
        assertEquals(payload.size.toLong(), reports.last().second)
    }

    @Test
    fun failedDownloadsLeaveNoPartialFile() {
        // body 为 null：应答声明 Content-Length 1024 但只写 16 字节，模拟中断
        routes["/truncated"] = 200 to null
        val destination = File(temporaryFolder.root, "update/DiPlay.apk")
        try {
            UpdateClient.download("$root/truncated", destination) { _, _ -> }
            throw AssertionError("expected an IOException")
        } catch (expected: java.io.IOException) {
            assertTrue(!destination.exists())
        }
    }

    // InputStream.readLine 扩展：逐字节读取一行（作者：TRAE AI）
    private fun InputStream.readLine(): String? {
        val builder = StringBuilder()
        while (true) {
            val value = read()
            if (value == -1 || value == 10) break
            if (value == 13) continue
            builder.append(value.toChar())
        }
        return if (builder.isEmpty() && available() == 0) null else builder.toString()
    }
}
