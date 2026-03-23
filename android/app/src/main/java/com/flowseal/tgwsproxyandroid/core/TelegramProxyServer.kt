package com.flowseal.tgwsproxyandroid.core

import android.os.Build
import android.os.SystemClock
import android.util.Base64
import com.flowseal.tgwsproxyandroid.ProxyConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class TelegramProxyServer(
    private val config: ProxyConfig,
    private val onStarted: () -> Unit = {},
    private val onInfo: (String, String) -> Unit = { _, _ -> },
    private val onWarn: (String, String) -> Unit = { _, _ -> },
    private val onError: (String, String, Throwable?) -> Unit = { _, _, _ -> },
    private val onDebug: (String, String) -> Unit = { _, _ -> },
) {
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val wsBlacklist = Collections.newSetFromMap(ConcurrentHashMap<DcKey, Boolean>())
    private val dcFailUntil = ConcurrentHashMap<DcKey, Long>()
    private val stats = ProxyStats()
    private val wsPool = WsPool()
    private val stopped = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    suspend fun run() {
        try {
            withContext(Dispatchers.IO) {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(config.host, config.port))
                }
                serverSocket = server

                info("server", "=".repeat(60))
                info("server", "Telegram WS Bridge Proxy для Android")
                info("server", "Слушаю ${config.host}:${config.port}")
                info("server", "Целевые DC IP:")
                config.dcIp.forEach { (dc, ip) ->
                    info("server", "  DC$dc: $ip")
                }
                info("server", "=".repeat(60))

                onStarted()
                serverScope.launch { statsLoop() }
                wsPool.warmup(config.dcIp)

                while (serverScope.isActive && !stopped.get()) {
                    val client = try {
                        server.accept()
                    } catch (e: SocketException) {
                        if (stopped.get()) {
                            break
                        }
                        throw e
                    }

                    configureSocket(client)
                    activeSockets += client
                    serverScope.launch {
                        handleClient(client)
                    }
                }
            }
        } finally {
            stopInternal()
        }
    }

    suspend fun stop() {
        stopInternal()
    }

    private suspend fun stopInternal() {
        if (!stopped.compareAndSet(false, true)) {
            return
        }

        runCatching { serverSocket?.close() }
        serverSocket = null

        activeSockets.toList().forEach { socket ->
            closeQuietly(socket)
        }
        activeSockets.clear()

        wsPool.closeAll()
        serverScope.cancel()
    }

    private suspend fun statsLoop() {
        while (serverScope.isActive && !stopped.get()) {
            delay(STATS_INTERVAL_MS)
            val blacklist = wsBlacklist
                .sortedBy { it.dc * 10 + if (it.isMedia) 1 else 0 }
                .joinToString(separator = ", ") {
                    "DC${it.dc}${if (it.isMedia) "m" else ""}"
                }
                .ifBlank { "нет" }
            info("stats", "${stats.summary()} | ws_bl: $blacklist")
        }
    }

    private suspend fun handleClient(client: Socket) {
        val label = client.remoteSocketAddress?.toString()?.removePrefix("/") ?: "?"
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())

        try {
            stats.connectionsTotal.incrementAndGet()

            val greeting = input.readExact(2)
            if (greeting[0].u8() != 5) {
                debug("client", "[$label] не SOCKS5 (ver=${greeting[0].u8()})")
                return
            }

            val methodCount = greeting[1].u8()
            input.readExact(methodCount)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            val request = input.readExact(4)
            val cmd = request[1].u8()
            val atyp = request[3].u8()
            if (cmd != 1) {
                output.write(socks5Reply(0x07))
                output.flush()
                return
            }

            val dst = when (atyp) {
                1 -> ipv4(input.readExact(4))
                3 -> {
                    val length = input.readExact(1)[0].u8()
                    String(input.readExact(length), StandardCharsets.US_ASCII)
                }

                4 -> InetAddress.getByAddress(input.readExact(16)).hostAddress.orEmpty()
                else -> {
                    output.write(socks5Reply(0x08))
                    output.flush()
                    return
                }
            }

            val port = ByteBuffer.wrap(input.readExact(2))
                .order(ByteOrder.BIG_ENDIAN)
                .short
                .toInt() and 0xFFFF

            if (dst.contains(":")) {
                warn("client", "[$label] обнаружен IPv6-адрес назначения: $dst:$port")
                output.write(socks5Reply(0x05))
                output.flush()
                return
            }

            if (!isTelegramIp(dst)) {
                handlePassthrough(client, input, output, dst, port, label)
                return
            }

            output.write(socks5Reply(0x00))
            output.flush()

            val init = try {
                input.readExact(64)
            } catch (_: EOFException) {
                debug("client", "[$label] клиент отключился до init-пакета")
                return
            }

            if (isHttpTransport(init)) {
                stats.connectionsHttpRejected.incrementAndGet()
                debug("client", "[$label] HTTP transport отклонён для $dst:$port")
                return
            }

            var route = dcFromInit(init)
            var proxiedInit = init
            var initPatched = false

            if (route == null) {
                val mapped = IP_TO_DC[dst]
                if (mapped != null && config.dcIp.containsKey(mapped.first)) {
                    route = DcRoute(mapped.first, mapped.second)
                    proxiedInit = patchInitDc(
                        init,
                        if (mapped.second) mapped.first else -mapped.first,
                    )
                    initPatched = true
                    debug("client", "[$label] init-пакет исправлен для DC${mapped.first}")
                }
            }

            if (route == null || !config.dcIp.containsKey(route.dc)) {
                warn("client", "[$label] неизвестный DC для $dst:$port, перехожу на TCP fallback")
                tcpFallback(client, input, output, dst, port, proxiedInit, label, route)
                return
            }

            val dcKey = DcKey(route.dc, route.isMedia)
            if (wsBlacklist.contains(dcKey)) {
                debug("client", "[$label] DC${route.dc}${mediaTag(route.isMedia)} в чёрном списке")
                tcpFallback(client, input, output, dst, port, proxiedInit, label, route)
                return
            }

            val wsTimeout = if (SystemClock.elapsedRealtime() < (dcFailUntil[dcKey] ?: 0L)) {
                WS_FAIL_TIMEOUT_MS
            } else {
                WS_TIMEOUT_MS
            }

            val domains = wsDomains(route.dc, route.isMedia)
            val targetIp = config.dcIp.getValue(route.dc)

            var ws = wsPool.get(route.dc, route.isMedia, targetIp, domains)
            var redirectFailure = false
            var allRedirects = true

            if (ws == null) {
                for (domain in domains) {
                    val url = "wss://$domain/apiws"
                    info(
                        "ws",
                        "[$label] DC${route.dc}${mediaTag(route.isMedia)} ($dst:$port) -> $url via $targetIp",
                    )
                    try {
                        ws = RawWebSocket.connect(targetIp, domain, timeoutMillis = wsTimeout)
                        allRedirects = false
                        break
                    } catch (e: WsHandshakeException) {
                        stats.wsErrors.incrementAndGet()
                        if (e.isRedirect) {
                            redirectFailure = true
                            warn(
                                "ws",
                                "[$label] DC${route.dc}${mediaTag(route.isMedia)} получил ${e.statusCode} от $domain -> ${e.location ?: "?"}",
                            )
                        } else {
                            allRedirects = false
                            warn(
                                "ws",
                                "[$label] DC${route.dc}${mediaTag(route.isMedia)} ошибка handshake: ${e.statusLine}",
                            )
                        }
                    } catch (t: Throwable) {
                        stats.wsErrors.incrementAndGet()
                        allRedirects = false
                        warn(
                            "ws",
                            "[$label] DC${route.dc}${mediaTag(route.isMedia)} ошибка подключения: ${t.message ?: t::class.java.simpleName}",
                        )
                    }
                }
            } else {
                info(
                    "ws",
                    "[$label] DC${route.dc}${mediaTag(route.isMedia)} ($dst:$port) -> pool hit via $targetIp",
                )
            }

            if (ws == null) {
                if (redirectFailure && allRedirects) {
                    wsBlacklist += dcKey
                    warn("ws", "[$label] DC${route.dc}${mediaTag(route.isMedia)} занесён в чёрный список WS (везде 302)")
                } else {
                    dcFailUntil[dcKey] = SystemClock.elapsedRealtime() + DC_FAIL_COOLDOWN_MS
                }
                info("ws", "[$label] перехожу на TCP для DC${route.dc}${mediaTag(route.isMedia)}")
                tcpFallback(client, input, output, dst, port, proxiedInit, label, route)
                return
            }

            dcFailUntil.remove(dcKey)
            stats.connectionsWs.incrementAndGet()

            val splitter = if (initPatched) MsgSplitter(proxiedInit) else null
            ws.send(proxiedInit)
            bridgeWs(client, input, output, ws, label, route, dst, port, splitter)
        } catch (_: EOFException) {
            debug("client", "[$label] клиент отключился")
        } catch (_: SocketException) {
            debug("client", "[$label] сокет закрыт")
        } catch (t: Throwable) {
            error("client", "[$label] непредвиденная ошибка", t)
        } finally {
            activeSockets -= client
            closeQuietly(client)
        }
    }

    private suspend fun handlePassthrough(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        dst: String,
        port: Int,
        label: String,
    ) {
        stats.connectionsPassthrough.incrementAndGet()
        debug("passthrough", "[$label] прямой passthrough -> $dst:$port")

        val remote = Socket()
        activeSockets += remote
        try {
            remote.connect(InetSocketAddress(dst, port), SOCKET_TIMEOUT_MS)
            configureSocket(remote)
        } catch (t: Throwable) {
            warn("passthrough", "[$label] не удалось подключиться к $dst:$port: ${t.message}")
            closeQuietly(remote)
            activeSockets -= remote
            output.write(socks5Reply(0x05))
            output.flush()
            return
        }

        output.write(socks5Reply(0x00))
        output.flush()

        try {
            bridgeTcp(
                client = client,
                input = input,
                output = output,
                remote = remote,
                remoteInput = BufferedInputStream(remote.getInputStream()),
                remoteOutput = BufferedOutputStream(remote.getOutputStream()),
                label = label,
            )
        } finally {
            activeSockets -= remote
            closeQuietly(remote)
        }
    }

    private suspend fun tcpFallback(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        dst: String,
        port: Int,
        init: ByteArray,
        label: String,
        route: DcRoute?,
    ) {
        val remote = Socket()
        activeSockets += remote
        try {
            remote.connect(InetSocketAddress(dst, port), SOCKET_TIMEOUT_MS)
            configureSocket(remote)
        } catch (t: Throwable) {
            warn("tcp", "[$label] не удалось подключиться по fallback к $dst:$port: ${t.message}")
            closeQuietly(remote)
            activeSockets -= remote
            return
        }

        stats.connectionsTcpFallback.incrementAndGet()
        try {
            val remoteInput = BufferedInputStream(remote.getInputStream())
            val remoteOutput = BufferedOutputStream(remote.getOutputStream())
            remoteOutput.write(init)
            remoteOutput.flush()

            bridgeTcp(
                client = client,
                input = input,
                output = output,
                remote = remote,
                remoteInput = remoteInput,
                remoteOutput = remoteOutput,
                label = label,
                route = route,
                dst = dst,
                port = port,
            )
        } finally {
            activeSockets -= remote
            closeQuietly(remote)
        }
    }

    private suspend fun bridgeWs(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        ws: RawWebSocket,
        label: String,
        route: DcRoute,
        dst: String,
        port: Int,
        splitter: MsgSplitter?,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        val upBytes = AtomicLong(0)
        val downBytes = AtomicLong(0)
        val upPackets = AtomicLong(0)
        val downPackets = AtomicLong(0)
        val finished = CompletableDeferred<Unit>()

        val jobs = listOf(
            serverScope.launch {
                try {
                    val buffer = ByteArray(BRIDGE_BUFFER_SIZE)
                    while (isActive) {
                        val count = input.read(buffer)
                        if (count <= 0) {
                            break
                        }
                        val chunk = buffer.copyOf(count)
                        stats.bytesUp.addAndGet(count.toLong())
                        upBytes.addAndGet(count.toLong())
                        upPackets.incrementAndGet()

                        if (splitter != null) {
                            val parts = splitter.split(chunk)
                            if (parts.size > 1) {
                                ws.sendBatch(parts)
                            } else {
                                ws.send(parts.first())
                            }
                        } else {
                            ws.send(chunk)
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    finished.complete(Unit)
                }
            },
            serverScope.launch {
                try {
                    while (isActive) {
                        val data = ws.recv() ?: break
                        stats.bytesDown.addAndGet(data.size.toLong())
                        downBytes.addAndGet(data.size.toLong())
                        downPackets.incrementAndGet()
                        output.write(data)
                        output.flush()
                    }
                } catch (_: Throwable) {
                } finally {
                    finished.complete(Unit)
                }
            },
        )

        try {
            finished.await()
        } finally {
            jobs.forEach { it.cancel() }
            jobs.joinAll()
            ws.close()
            closeQuietly(client)
            info(
                "ws",
                "[$label] DC${route.dc}${mediaTag(route.isMedia)} ($dst:$port) WS закрыт: ^${humanBytes(upBytes.get())} (${upPackets.get()} пак.) v${humanBytes(downBytes.get())} (${downPackets.get()} пак.) за ${((SystemClock.elapsedRealtime() - startedAt) / 1000.0).format1()}с",
            )
        }
    }

    private suspend fun bridgeTcp(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        remote: Socket,
        remoteInput: InputStream,
        remoteOutput: OutputStream,
        label: String,
        route: DcRoute? = null,
        dst: String? = null,
        port: Int? = null,
    ) {
        val finished = CompletableDeferred<Unit>()

        val jobs = listOf(
            serverScope.launch {
                try {
                    pipe(input, remoteOutput, upward = true, label = label, direction = "client->remote")
                } finally {
                    finished.complete(Unit)
                }
            },
            serverScope.launch {
                try {
                    pipe(remoteInput, output, upward = false, label = label, direction = "remote->client")
                } finally {
                    finished.complete(Unit)
                }
            },
        )

        try {
            finished.await()
        } finally {
            jobs.forEach { it.cancel() }
            jobs.joinAll()
            closeQuietly(remote)
            closeQuietly(client)
            if (route != null && dst != null && port != null) {
                info("tcp", "[$label] DC${route.dc}${mediaTag(route.isMedia)} TCP fallback завершён для $dst:$port")
            }
        }
    }

    private fun pipe(
        input: InputStream,
        output: OutputStream,
        upward: Boolean,
        label: String,
        direction: String,
    ) {
        try {
            val buffer = ByteArray(BRIDGE_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) {
                    break
                }
                if (upward) {
                    stats.bytesUp.addAndGet(count.toLong())
                } else {
                    stats.bytesDown.addAndGet(count.toLong())
                }
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (_: EOFException) {
            debug("tcp", "[$label] поток $direction завершён по EOF")
        } catch (_: SocketException) {
            debug("tcp", "[$label] поток $direction завершён из-за закрытия сокета")
        } catch (_: IOException) {
            debug("tcp", "[$label] поток $direction завершён по I/O ошибке")
        }
    }

    private fun isTelegramIp(ip: String): Boolean {
        val bytes = ipv4Bytes(ip) ?: return false
        val value = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
        return TG_RANGES.any { range -> value in range }
    }

    private fun isHttpTransport(data: ByteArray): Boolean {
        return data.startsWithAscii("POST ") ||
            data.startsWithAscii("GET ") ||
            data.startsWithAscii("HEAD ") ||
            data.startsWithAscii("OPTIONS ")
    }

    private fun dcFromInit(data: ByteArray): DcRoute? {
        return runCatching {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(data.copyOfRange(8, 40), "AES"),
                    IvParameterSpec(data.copyOfRange(40, 56)),
                )
            }
            val keystream = cipher.update(ByteArray(64))
            val plain = ByteArray(8)
            for (i in 0 until 8) {
                plain[i] = (data[56 + i].toInt() xor keystream[56 + i].toInt()).toByte()
            }
            val buffer = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
            val proto = buffer.int
            val dcRaw = buffer.short.toInt()
            if (proto in VALID_PROTOS) {
                val dc = kotlin.math.abs(dcRaw)
                if (dc in 1..5 || dc == 203) {
                    DcRoute(dc = dc, isMedia = dcRaw < 0)
                } else {
                    null
                }
            } else {
                null
            }
        }.getOrNull()
    }

    private fun patchInitDc(data: ByteArray, dc: Int): ByteArray {
        if (data.size < 64) {
            return data
        }
        return runCatching {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(data.copyOfRange(8, 40), "AES"),
                    IvParameterSpec(data.copyOfRange(40, 56)),
                )
            }
            val keystream = cipher.update(ByteArray(64))
            val dcBytes = ByteBuffer.allocate(2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(dc.toShort())
                .array()
            val patched = data.copyOf()
            patched[60] = (keystream[60].toInt() xor dcBytes[0].toInt()).toByte()
            patched[61] = (keystream[61].toInt() xor dcBytes[1].toInt()).toByte()
            patched
        }.getOrDefault(data)
    }

    private fun socks5Reply(status: Int): ByteArray = byteArrayOf(
        0x05,
        status.toByte(),
        0x00,
        0x01,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
    )

    private fun wsDomains(dc: Int, isMedia: Boolean): List<String> {
        val actualDc = DC_OVERRIDES[dc] ?: dc
        return if (isMedia) {
            listOf("kws$actualDc-1.web.telegram.org", "kws$actualDc.web.telegram.org")
        } else {
            listOf("kws$actualDc.web.telegram.org", "kws$actualDc-1.web.telegram.org")
        }
    }

    private fun configureSocket(socket: Socket) {
        runCatching {
            socket.tcpNoDelay = true
            socket.receiveBufferSize = config.bufferKb * 1024
            socket.sendBufferSize = config.bufferKb * 1024
            socket.keepAlive = true
        }
    }

    private fun closeQuietly(closeable: Closeable?) {
        runCatching { closeable?.close() }
    }

    private fun info(source: String, message: String) = onInfo(source, message)
    private fun warn(source: String, message: String) = onWarn(source, message)
    private fun error(source: String, message: String, throwable: Throwable? = null) =
        onError(source, message, throwable)
    private fun debug(source: String, message: String) = onDebug(source, message)

    private fun mediaTag(isMedia: Boolean): String = if (isMedia) " медиа" else ""

    private inner class WsPool {
        private val idle = ConcurrentHashMap<DcKey, ArrayDeque<PooledWs>>()
        private val refilling = Collections.newSetFromMap(ConcurrentHashMap<DcKey, Boolean>())

        suspend fun get(
            dc: Int,
            isMedia: Boolean,
            targetIp: String,
            domains: List<String>,
        ): RawWebSocket? {
            val key = DcKey(dc, isMedia)
            val now = SystemClock.elapsedRealtime()
            val bucket = idle.getOrPut(key) { ArrayDeque() }

            synchronized(bucket) {
                while (bucket.isNotEmpty()) {
                    val pooled = bucket.removeFirst()
                    val age = now - pooled.createdAtMs
                    if (age > WS_POOL_MAX_AGE_MS || pooled.ws.isClosed()) {
                        serverScope.launch { pooled.ws.close() }
                        continue
                    }
                    stats.poolHits.incrementAndGet()
                    debug("pool", "Попадание в WS pool для DC$dc${mediaTag(isMedia)}")
                    scheduleRefill(key, targetIp, domains)
                    return pooled.ws
                }
            }

            stats.poolMisses.incrementAndGet()
            scheduleRefill(key, targetIp, domains)
            return null
        }

        fun warmup(dcIp: Map<Int, String>) {
            dcIp.forEach { (dc, targetIp) ->
                scheduleRefill(DcKey(dc, false), targetIp, wsDomains(dc, false))
                scheduleRefill(DcKey(dc, true), targetIp, wsDomains(dc, true))
            }
            info("pool", "Предзаполнение WS pool запущено для ${dcIp.size} DC")
        }

        suspend fun closeAll() {
            idle.values.flatMap { bucket ->
                synchronized(bucket) { bucket.toList() }
            }.forEach { pooled ->
                runCatching { pooled.ws.close() }
            }
            idle.clear()
            refilling.clear()
        }

        private fun scheduleRefill(
            key: DcKey,
            targetIp: String,
            domains: List<String>,
        ) {
            if (config.poolSize <= 0 || !refilling.add(key)) {
                return
            }
            serverScope.launch {
                try {
                    val bucket = idle.getOrPut(key) { ArrayDeque() }
                    val needed = synchronized(bucket) {
                        (config.poolSize - bucket.size).coerceAtLeast(0)
                    }
                    repeat(needed) {
                        val ws = connectOne(targetIp, domains) ?: return@repeat
                        synchronized(bucket) {
                            bucket.addLast(PooledWs(ws, SystemClock.elapsedRealtime()))
                        }
                    }
                    debug(
                        "pool",
                        "WS pool пополнен для DC${key.dc}${mediaTag(key.isMedia)}: готово ${synchronized(bucket) { bucket.size }}",
                    )
                } finally {
                    refilling.remove(key)
                }
            }
        }

        private suspend fun connectOne(targetIp: String, domains: List<String>): RawWebSocket? {
            for (domain in domains) {
                try {
                    return RawWebSocket.connect(targetIp, domain, timeoutMillis = 8_000)
                } catch (e: WsHandshakeException) {
                    if (!e.isRedirect) {
                        return null
                    }
                } catch (_: Throwable) {
                    return null
                }
            }
            return null
        }
    }

    private data class ProxyStats(
        val connectionsTotal: AtomicLong = AtomicLong(0),
        val connectionsWs: AtomicLong = AtomicLong(0),
        val connectionsTcpFallback: AtomicLong = AtomicLong(0),
        val connectionsHttpRejected: AtomicLong = AtomicLong(0),
        val connectionsPassthrough: AtomicLong = AtomicLong(0),
        val wsErrors: AtomicLong = AtomicLong(0),
        val bytesUp: AtomicLong = AtomicLong(0),
        val bytesDown: AtomicLong = AtomicLong(0),
        val poolHits: AtomicLong = AtomicLong(0),
        val poolMisses: AtomicLong = AtomicLong(0),
    ) {
        fun summary(): String {
            val hits = poolHits.get()
            val misses = poolMisses.get()
            return "всего=${connectionsTotal.get()} ws=${connectionsWs.get()} tcp_fb=${connectionsTcpFallback.get()} " +
                "http_skip=${connectionsHttpRejected.get()} pass=${connectionsPassthrough.get()} err=${wsErrors.get()} " +
                "pool=$hits/${hits + misses} up=${humanBytes(bytesUp.get())} down=${humanBytes(bytesDown.get())}"
        }
    }

    private data class DcRoute(val dc: Int, val isMedia: Boolean)
    private data class DcKey(val dc: Int, val isMedia: Boolean)
    private data class PooledWs(val ws: RawWebSocket, val createdAtMs: Long)

    private companion object {
        const val SOCKET_TIMEOUT_MS = 10_000
        const val WS_TIMEOUT_MS = 10_000
        const val WS_FAIL_TIMEOUT_MS = 2_000
        const val DC_FAIL_COOLDOWN_MS = 30_000L
        const val WS_POOL_MAX_AGE_MS = 120_000L
        const val STATS_INTERVAL_MS = 60_000L
        const val BRIDGE_BUFFER_SIZE = 65_536

        val VALID_PROTOS = setOf(0xEFEFEFEF.toInt(), 0xEEEEEEEE.toInt(), 0xDDDDDDDD.toInt())

        val TG_RANGES = listOf(
            ipv4Range("185.76.151.0", "185.76.151.255"),
            ipv4Range("149.154.160.0", "149.154.175.255"),
            ipv4Range("91.105.192.0", "91.105.193.255"),
            ipv4Range("91.108.0.0", "91.108.255.255"),
        )

        val IP_TO_DC: Map<String, Pair<Int, Boolean>> = mapOf(
            "149.154.175.50" to (1 to false),
            "149.154.175.51" to (1 to false),
            "149.154.175.53" to (1 to false),
            "149.154.175.54" to (1 to false),
            "149.154.175.52" to (1 to true),
            "149.154.167.41" to (2 to false),
            "149.154.167.50" to (2 to false),
            "149.154.167.51" to (2 to false),
            "149.154.167.220" to (2 to false),
            "95.161.76.100" to (2 to false),
            "149.154.167.151" to (2 to true),
            "149.154.167.222" to (2 to true),
            "149.154.167.223" to (2 to true),
            "149.154.162.123" to (2 to true),
            "149.154.175.100" to (3 to false),
            "149.154.175.101" to (3 to false),
            "149.154.175.102" to (3 to true),
            "149.154.167.91" to (4 to false),
            "149.154.167.92" to (4 to false),
            "149.154.164.250" to (4 to true),
            "149.154.166.120" to (4 to true),
            "149.154.166.121" to (4 to true),
            "149.154.167.118" to (4 to true),
            "149.154.165.111" to (4 to true),
            "91.108.56.100" to (5 to false),
            "91.108.56.101" to (5 to false),
            "91.108.56.116" to (5 to false),
            "91.108.56.126" to (5 to false),
            "149.154.171.5" to (5 to false),
            "91.108.56.102" to (5 to true),
            "91.108.56.128" to (5 to true),
            "91.108.56.151" to (5 to true),
            "91.105.192.100" to (203 to false),
        )

        val DC_OVERRIDES = mapOf(
            203 to 2,
        )

        fun ipv4Range(start: String, end: String): LongRange {
            val from = ipv4Bytes(start)!!.toLongValue()
            val to = ipv4Bytes(end)!!.toLongValue()
            return from..to
        }
    }
}

private class MsgSplitter(initData: ByteArray) {
    private val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(initData.copyOfRange(8, 40), "AES"),
            IvParameterSpec(initData.copyOfRange(40, 56)),
        )
    }

    init {
        cipher.update(ByteArray(64))
    }

    fun split(chunk: ByteArray): List<ByteArray> {
        val plain = cipher.update(chunk) ?: return listOf(chunk)
        val boundaries = mutableListOf<Int>()
        var pos = 0

        while (pos < plain.size) {
            val first = plain[pos].u8()
            val msgLen: Int
            if (first == 0x7F) {
                if (pos + 4 > plain.size) {
                    break
                }
                msgLen = (ByteBuffer.wrap(plain, pos + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int and 0x00FFFFFF) * 4
                pos += 4
            } else {
                msgLen = first * 4
                pos += 1
            }

            if (msgLen == 0 || pos + msgLen > plain.size) {
                break
            }

            pos += msgLen
            boundaries += pos
        }

        if (boundaries.size <= 1) {
            return listOf(chunk)
        }

        val parts = mutableListOf<ByteArray>()
        var previous = 0
        for (boundary in boundaries) {
            parts += chunk.copyOfRange(previous, boundary)
            previous = boundary
        }
        if (previous < chunk.size) {
            parts += chunk.copyOfRange(previous, chunk.size)
        }
        return parts
    }
}

private class WsHandshakeException(
    val statusCode: Int,
    val statusLine: String,
    val headers: Map<String, String> = emptyMap(),
    val location: String? = null,
) : Exception("HTTP $statusCode: $statusLine") {
    val isRedirect: Boolean
        get() = statusCode in setOf(301, 302, 303, 307, 308)
}

private class RawWebSocket(
    private val socket: Socket,
    private val input: BufferedInputStream,
    private val output: BufferedOutputStream,
) {
    private val random = SecureRandom()
    private val closed = AtomicBoolean(false)

    suspend fun send(data: ByteArray) {
        ensureOpen()
        withContext(Dispatchers.IO) {
            output.write(buildFrame(OP_BINARY, data, mask = true))
            output.flush()
        }
    }

    suspend fun sendBatch(parts: List<ByteArray>) {
        ensureOpen()
        withContext(Dispatchers.IO) {
            parts.forEach { part ->
                output.write(buildFrame(OP_BINARY, part, mask = true))
            }
            output.flush()
        }
    }

    suspend fun recv(): ByteArray? = withContext(Dispatchers.IO) {
        while (!closed.get()) {
            val (opcode, payload) = readFrame()
            when (opcode) {
                OP_CLOSE -> {
                    closed.set(true)
                    runCatching {
                        output.write(buildFrame(OP_CLOSE, payload.copyOfRange(0, minOf(payload.size, 2)), mask = true))
                        output.flush()
                    }
                    return@withContext null
                }

                OP_PING -> {
                    runCatching {
                        output.write(buildFrame(OP_PONG, payload, mask = true))
                        output.flush()
                    }
                }

                OP_PONG -> Unit
                OP_TEXT, OP_BINARY -> return@withContext payload
                else -> Unit
            }
        }
        null
    }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        withContext(Dispatchers.IO) {
            runCatching {
                output.write(buildFrame(OP_CLOSE, ByteArray(0), mask = true))
                output.flush()
            }
            runCatching { socket.close() }
        }
    }

    fun isClosed(): Boolean = closed.get() || socket.isClosed

    private fun ensureOpen() {
        if (closed.get()) {
            throw SocketException("WebSocket closed")
        }
    }

    private fun buildFrame(opcode: Int, data: ByteArray, mask: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x80 or opcode)
        val length = data.size.toLong()

        when {
            length < 126 -> out.write((if (mask) 0x80 else 0x00) or length.toInt())
            length < 65536 -> {
                out.write((if (mask) 0x80 else 0x00) or 126)
                out.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(length.toShort()).array())
            }

            else -> {
                out.write((if (mask) 0x80 else 0x00) or 127)
                out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(length).array())
            }
        }

        if (!mask) {
            out.write(data)
            return out.toByteArray()
        }

        val maskKey = ByteArray(4).also(random::nextBytes)
        out.write(maskKey)
        out.write(xorMask(data, maskKey))
        return out.toByteArray()
    }

    private fun readFrame(): Pair<Int, ByteArray> {
        val header = input.readExact(2)
        val opcode = header[0].toInt() and 0x0F
        val masked = (header[1].toInt() and 0x80) != 0
        val payloadLen = when (val len = header[1].toInt() and 0x7F) {
            126 -> ByteBuffer.wrap(input.readExact(2)).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            127 -> ByteBuffer.wrap(input.readExact(8)).order(ByteOrder.BIG_ENDIAN).long.toInt()
            else -> len
        }

        val maskKey = if (masked) input.readExact(4) else null
        val payload = input.readExact(payloadLen)
        return opcode to if (maskKey != null) xorMask(payload, maskKey) else payload
    }

    companion object {
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA

        suspend fun connect(
            ip: String,
            domain: String,
            path: String = "/apiws",
            timeoutMillis: Int = 10_000,
        ): RawWebSocket = withContext(Dispatchers.IO) {
            val rawSocket = Socket()
            rawSocket.tcpNoDelay = true
            rawSocket.connect(InetSocketAddress(ip, 443), timeoutMillis)

            val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(rawSocket, domain, 443, true) as SSLSocket
            sslSocket.useClientMode = true
            sslSocket.soTimeout = timeoutMillis

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val params = sslSocket.sslParameters
                params.serverNames = listOf(SNIHostName(domain))
                params.endpointIdentificationAlgorithm = null
                sslSocket.sslParameters = params
            }

            sslSocket.startHandshake()

            val input = BufferedInputStream(sslSocket.getInputStream())
            val output = BufferedOutputStream(sslSocket.getOutputStream())

            val wsKey = Base64.encodeToString(ByteArray(16).also(SecureRandom()::nextBytes), Base64.NO_WRAP)
            val request = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: $domain\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $wsKey\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("Sec-WebSocket-Protocol: binary\r\n")
                append("Origin: https://web.telegram.org\r\n")
                append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.UTF_8)

            output.write(request)
            output.flush()

            val responseLines = mutableListOf<String>()
            while (true) {
                val line = input.readAsciiLine() ?: break
                if (line.isEmpty()) {
                    break
                }
                responseLines += line
            }

            if (responseLines.isEmpty()) {
                sslSocket.close()
                throw WsHandshakeException(0, "empty response")
            }

            val firstLine = responseLines.first()
            val parts = firstLine.split(" ", limit = 3)
            val statusCode = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (statusCode == 101) {
                sslSocket.soTimeout = 0
                return@withContext RawWebSocket(sslSocket, input, output)
            }

            val headers = responseLines.drop(1)
                .mapNotNull { line ->
                    val index = line.indexOf(':')
                    if (index <= 0) {
                        null
                    } else {
                        line.substring(0, index).trim().lowercase(Locale.US) to line.substring(index + 1).trim()
                    }
                }
                .toMap()

            sslSocket.close()
            throw WsHandshakeException(
                statusCode = statusCode,
                statusLine = firstLine,
                headers = headers,
                location = headers["location"],
            )
        }

        private fun xorMask(data: ByteArray, mask: ByteArray): ByteArray {
            val result = ByteArray(data.size)
            for (index in data.indices) {
                result[index] = (data[index].toInt() xor mask[index % 4].toInt()).toByte()
            }
            return result
        }
    }
}

private fun InputStream.readExact(length: Int): ByteArray {
    val buffer = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(buffer, offset, length - offset)
        if (read < 0) {
            throw EOFException("Expected $length bytes, got $offset")
        }
        offset += read
    }
    return buffer
}

private fun InputStream.readAsciiLine(): String? {
    val buffer = ByteArrayOutputStream()
    while (true) {
        val value = read()
        if (value < 0) {
            return if (buffer.size() == 0) null else buffer.toString(StandardCharsets.UTF_8.name())
        }
        if (value == '\n'.code) {
            val bytes = buffer.toByteArray()
            val trimmed = if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.copyOf(bytes.size - 1) else bytes
            return String(trimmed, StandardCharsets.UTF_8)
        }
        buffer.write(value)
    }
}

private fun ipv4(bytes: ByteArray): String =
    bytes.joinToString(separator = ".") { (it.toInt() and 0xFF).toString() }

private fun ipv4Bytes(ip: String): ByteArray? {
    val parts = ip.split(".")
    if (parts.size != 4) {
        return null
    }
    return runCatching {
        ByteArray(4) { index ->
            val value = parts[index].toInt()
            if (value !in 0..255) {
                throw IllegalArgumentException("Invalid IPv4")
            }
            value.toByte()
        }
    }.getOrNull()
}

private fun ByteArray.toLongValue(): Long =
    ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL

private fun ByteArray.startsWithAscii(prefix: String): Boolean {
    val prefixBytes = prefix.toByteArray(StandardCharsets.US_ASCII)
    if (size < prefixBytes.size) {
        return false
    }
    for (index in prefixBytes.indices) {
        if (this[index] != prefixBytes[index]) {
            return false
        }
    }
    return true
}

private fun Double.format1(): String = String.format(Locale.US, "%.1f", this)

private fun humanBytes(value: Long): String {
    var current = value.toDouble()
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    for (unit in units) {
        if (kotlin.math.abs(current) < 1024.0) {
            return String.format(Locale.US, "%.1f%s", current, unit)
        }
        current /= 1024.0
    }
    return String.format(Locale.US, "%.1fPB", current)
}

private fun Byte.u8(): Int = toInt() and 0xFF
