package com.shilapi.xcertplay.transport

import android.util.Log
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Blocking, plaintext Lockdown pairing over an already-open USBMUX host.
 *
 * Call [pair] from a worker thread, never Android's main thread. This client owns only the
 * connection it creates: it closes the plist channel (and therefore that connection) after every
 * outcome, but leaves [Iap2UsbMuxHost] open for its caller. It neither persists material nor
 * starts a session, negotiates TLS, starts a service, or establishes any CarPlay/iAP2 session.
 */
class LockdownPairingClient(
    private val host: Iap2UsbMuxHost,
    private val onDiagnostic: (String) -> Unit = {},
) {
    /**
     * Fetches the two pairing inputs, generates the local record, and sends plaintext Pair.
     *
     * [totalTimeoutMillis] covers the entire operation, including a pending pairing dialog.
     * [isCancelled] is checked between blocking protocol operations and while waiting to retry a
     * pending dialog; it cannot interrupt an already-blocking plist read, which is bounded by the
     * pair/step timeouts. It is intentionally a small synchronous callback rather than a coroutine.
     */
    @Throws(IphoneUsbException::class, LockdownPairingException::class, GeneralSecurityException::class)
    fun pair(
        label: String,
        hostId: String,
        systemBuid: String,
        totalTimeoutMillis: Long,
        isCancelled: () -> Boolean = { false },
    ): PairedRecord {
        require(label.isNotBlank()) { "label must not be blank" }
        require(hostId.isNotBlank()) { "hostId must not be blank" }
        require(systemBuid.isNotBlank()) { "systemBuid must not be blank" }
        require(totalTimeoutMillis in 1..MAXIMUM_TOTAL_TIMEOUT_MILLIS) {
            "totalTimeoutMillis must be between 1 and $MAXIMUM_TOTAL_TIMEOUT_MILLIS"
        }

        val deadline = Deadline(totalTimeoutMillis)
        var pairRecord: LockdownPairRecord? = null
        var transportAttempts = 0
        while (true) {
            checkCancelled(isCancelled)
            transportAttempts += 1
            val connectStartNanos = System.nanoTime()
            try {
                val connection = host.connect(
                    destinationPort = Iap2UsbMuxHost.LOCKDOWN_PORT,
                    timeoutMillis = transportStepTimeoutMillis(deadline),
                )
                diagnose(
                    "pair transport attempt=$transportAttempts connected port=${Iap2UsbMuxHost.LOCKDOWN_PORT} " +
                        "connectMs=${elapsedMillis(connectStartNanos)}",
                )
                LockdownPlistChannel(connection, defaultTimeoutMillis = transportStepTimeoutMillis(deadline))
                    .use { channel ->
                        setUntrustedHostBuid(channel, label, systemBuid, deadline, isCancelled)
                        val record = pairRecord ?: generatePairRecord(
                            channel = channel,
                            label = label,
                            hostId = hostId,
                            systemBuid = systemBuid,
                            deadline = deadline,
                            isCancelled = isCancelled,
                        ).also {
                            pairRecord = it
                            Log.i(
                                TAG,
                                "lockdown pair prepared hostId=uuid systemBuid=uuid " +
                                    "deviceKey=${it.devicePublicKeyPem.size} deviceCert=${it.deviceCertificatePem.size} " +
                                    "hostCert=${it.hostCertificatePem.size} rootCert=${it.rootCertificatePem.size}",
                            )
                        }
                        Log.i(TAG, "lockdown pair attempt=$transportAttempts")
                        // 信任对话框期间必须留在**同一条** lockdown 连接上反复发 Pair：
                        // 一旦断开重连，iPhone 侧这次配对会话随之作废，用户点下的"信任"永远回不到我们
                        // 这里（真机日志即为此签名：弹框→点信任→无下文）。只有传输层真的断了才重连。
                        var pairRequests = 0
                        while (true) {
                            checkCancelled(isCancelled)
                            pairRequests += 1
                            val requestStartNanos = System.nanoTime()
                            val response = channel.request(
                                pairRequest(label, record),
                                pairResponseTimeoutMillis(deadline),
                            )
                            val elapsed = elapsedMillis(requestStartNanos)
                            checkCancelled(isCancelled)
                            when (val error = response.errorCodeOrNull()) {
                                null -> {
                                    diagnose(
                                        "pair accepted attempt=$transportAttempts request=$pairRequests " +
                                            "elapsedMs=$elapsed",
                                    )
                                    return PairedRecord(record, response.entries["EscrowBag"].asOptionalData())
                                }
                                "PairingDialogResponsePending" -> {
                                    diagnose(
                                        "pair trust dialog pending request=$pairRequests elapsedMs=$elapsed; " +
                                            "keep waiting on the same connection for the user to tap Trust",
                                    )
                                    waitForRetry(deadline, isCancelled)
                                }
                                "UserDeniedPairing" -> throw LockdownPairingException.UserDeniedPairing
                                "PasswordProtected" -> throw LockdownPairingException.PasswordProtected
                                else -> throw LockdownPairingException.RemoteError(error)
                            }
                        }
                    }
            } catch (error: IphoneUsbException) {
                // 传输层错误（连接被拒、plist 帧超时/截断）才值得换一条连接重来；
                // 用户拒绝、密码保护等协议结论直接上抛。
                diagnose(
                    "pair transport attempt=$transportAttempts failed " +
                        "${error.javaClass.simpleName}: ${error.message}",
                )
                if (transportAttempts >= MAXIMUM_TRANSPORT_ATTEMPTS) throw error
                waitForRetry(deadline, isCancelled)
            }
        }
    }

    private fun generatePairRecord(
        channel: LockdownPlistChannel,
        label: String,
        hostId: String,
        systemBuid: String,
        deadline: Deadline,
        isCancelled: () -> Boolean,
    ): LockdownPairRecord {
        val devicePublicKey = getValue(channel, label, "DevicePublicKey", deadline, isCancelled)
            as? LockdownPlistValue.Data
            ?: throw LockdownPairingException.InvalidResponse("DevicePublicKey was not data")
        val wifiAddress = getValue(channel, label, "WiFiAddress", deadline, isCancelled)
            as? LockdownPlistValue.Text
            ?: throw LockdownPairingException.InvalidResponse("WiFiAddress was not text")
        return LockdownPairRecordGenerator.generate(
            devicePublicKeyPkcs1Pem = devicePublicKey.bytes,
            wifiAddress = wifiAddress.value,
            hostId = hostId,
            systemBuid = systemBuid,
        )
    }

    private fun pairRequest(
        label: String,
        pairRecord: LockdownPairRecord,
    ): LockdownPlistValue.Dictionary = LockdownPlistValue.Dictionary(
        linkedMapOf(
            "Label" to LockdownPlistValue.Text(label),
            "PairRecord" to pairRecord.toPairRequestDictionary(),
            "Request" to LockdownPlistValue.Text("Pair"),
            "ProtocolVersion" to LockdownPlistValue.Text("2"),
            "PairingOptions" to LockdownPlistValue.Dictionary(
                linkedMapOf("ExtendedPairingErrors" to LockdownPlistValue.Boolean(true)),
            ),
        ),
    )

    private fun setUntrustedHostBuid(
        channel: LockdownPlistChannel,
        label: String,
        systemBuid: String,
        deadline: Deadline,
        isCancelled: () -> Boolean,
    ) {
        checkCancelled(isCancelled)
        val response = channel.request(
            LockdownPlistValue.Dictionary(
                linkedMapOf(
                    "Label" to LockdownPlistValue.Text(label),
                    "Request" to LockdownPlistValue.Text("SetValue"),
                    "Key" to LockdownPlistValue.Text("UntrustedHostBUID"),
                    "Value" to LockdownPlistValue.Text(systemBuid),
                ),
            ),
            transportStepTimeoutMillis(deadline),
        )
        checkCancelled(isCancelled)
        response.errorCodeOrNull()?.let { throw LockdownPairingException.RemoteError(it) }
        diagnose("pair UntrustedHostBUID set")
    }

    private fun getValue(
        channel: LockdownPlistChannel,
        label: String,
        key: String,
        deadline: Deadline,
        isCancelled: () -> Boolean,
    ): LockdownPlistValue {
        checkCancelled(isCancelled)
        val startedNanos = System.nanoTime()
        val response = channel.request(
            LockdownPlistValue.Dictionary(
                linkedMapOf(
                    "Label" to LockdownPlistValue.Text(label),
                    "Request" to LockdownPlistValue.Text("GetValue"),
                    "Key" to LockdownPlistValue.Text(key),
                ),
            ),
            transportStepTimeoutMillis(deadline),
        )
        checkCancelled(isCancelled)
        response.errorCodeOrNull()?.let { throw LockdownPairingException.RemoteError(it) }
        diagnose("pair GetValue $key answered elapsedMs=${elapsedMillis(startedNanos)}")
        return response.entries["Value"]
            ?: throw LockdownPairingException.InvalidResponse("GetValue response omitted Value")
    }

    private fun LockdownPlistValue?.asOptionalData(): ByteArray? = when (this) {
        null -> null
        is LockdownPlistValue.Data -> bytes
        else -> throw LockdownPairingException.InvalidResponse("EscrowBag was not data")
    }

    private fun LockdownPlistValue.Dictionary.errorCodeOrNull(): String? {
        return when (val error = entries["Error"] ?: return null) {
            is LockdownPlistValue.Text -> error.value
            is LockdownPlistValue.Integer -> (entries["ErrorString"] as? LockdownPlistValue.Text)?.value
                ?: error.value.toString()
            else -> throw LockdownPairingException.InvalidResponse("Error was not text or integer")
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw LockdownPairingException.Cancelled
    }

    /**
     * 传输类步骤（TCP 连接、SetValue/GetValue）的超时。
     *
     * 5s 对 C140A + iPhone 太紧：手机要先把 lockdown 服务拉起来才回第一帧，实测 3.3-9.3s 的静默很常见，
     * 一超时就重连反而让手机侧反复复位。
     */
    private fun transportStepTimeoutMillis(deadline: Deadline): Long =
        minOf(TRANSPORT_STEP_TIMEOUT_MILLIS, deadline.remainingMillis())

    /**
     * Pair 请求的超时：这条请求要等人在 iPhone 上点"信任"，绝不能用秒级超时衡量。
     * 正常情况下手机几秒内回 `PairingDialogResponsePending`，真正的等待由重发循环承担。
     */
    private fun pairResponseTimeoutMillis(deadline: Deadline): Long =
        minOf(PAIR_RESPONSE_TIMEOUT_MILLIS, deadline.remainingMillis())

    private fun diagnose(message: String) {
        Log.i(TAG, message)
        runCatching { onDiagnostic(message) }
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0)

    private fun waitForRetry(deadline: Deadline, isCancelled: () -> Boolean) {
        var remaining = RETRY_INTERVAL_MILLIS
        while (remaining > 0) {
            checkCancelled(isCancelled)
            val sleepMillis = minOf(RETRY_CHECK_MILLIS, remaining, deadline.remainingMillis())
            try {
                Thread.sleep(sleepMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw LockdownPairingException.Cancelled
            }
            remaining -= sleepMillis
        }
    }

    private class Deadline(timeoutMillis: Long) {
        private val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND

        fun remainingMillis(): Long {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) throw LockdownPairingException.TimedOut
            return (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val RETRY_INTERVAL_MILLIS = 1_000L
        const val RETRY_CHECK_MILLIS = 100L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        /** TCP 连接与 SetValue/GetValue 这类纯传输步骤的预算。 */
        const val TRANSPORT_STEP_TIMEOUT_MILLIS = 20_000L
        /** Pair 请求可能要等人点"信任"，给足一轮弹框反应时间。 */
        const val PAIR_RESPONSE_TIMEOUT_MILLIS = 120_000L
        /** 传输层真的断了才换连接重来的次数上限（协议结论不重试）。 */
        const val MAXIMUM_TRANSPORT_ATTEMPTS = 5
        const val MAXIMUM_TOTAL_TIMEOUT_MILLIS = 5 * 60_000L
    }
}

/** Successful pairing material kept in memory only. Byte-array accessors return defensive copies. */
class PairedRecord internal constructor(
    val pairRecord: LockdownPairRecord,
    escrowBag: ByteArray?,
) {
    private val storedEscrowBag = escrowBag?.copyOf()

    val escrowBag: ByteArray?
        get() = storedEscrowBag?.copyOf()

    override fun toString(): String = "PairedRecord(redacted)"
}

/** Typed pairing failures; their messages never include pairing keys, certificates, or escrow data. */
sealed class LockdownPairingException(message: String) : IOException(message) {
    data object Cancelled : LockdownPairingException("Lockdown pairing was cancelled")
    data object TimedOut : LockdownPairingException("Lockdown pairing timed out")
    data object UserDeniedPairing : LockdownPairingException("The user denied Lockdown pairing")
    data object PasswordProtected : LockdownPairingException("The iPhone is password protected")
    class RemoteError(val code: String) : LockdownPairingException("Lockdown pairing failed: $code")
    class InvalidResponse(message: String) : LockdownPairingException("Invalid Lockdown pairing response: $message")
}
