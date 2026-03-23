package com.flowseal.tgwsproxyandroid

import android.content.Context

object ProxyConfigStore {
    private const val PREFS_NAME = "tg_ws_proxy_android"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_DC_IP = "dc_ip"
    private const val KEY_VERBOSE = "verbose"

    fun load(context: Context): ProxyConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dcText = prefs.getString(KEY_DC_IP, null)
        return ProxyConfig(
            host = prefs.getString(KEY_HOST, ProxyConfig.DEFAULT_HOST).orEmpty(),
            port = prefs.getInt(KEY_PORT, ProxyConfig.DEFAULT_PORT),
            dcIp = parseDcIpLines(dcText ?: formatDcIpLines(ProxyConfig.DEFAULT_DC_IP)),
            verbose = prefs.getBoolean(KEY_VERBOSE, false),
        )
    }

    fun save(context: Context, config: ProxyConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_HOST, config.host)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_DC_IP, formatDcIpLines(config.dcIp))
            .putBoolean(KEY_VERBOSE, config.verbose)
            .apply()
    }

    fun parseDcIpLines(raw: String): Map<Int, String> {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) {
            return ProxyConfig.DEFAULT_DC_IP
        }

        val result = linkedMapOf<Int, String>()
        cleaned.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val parts = line.split(":", limit = 2)
                require(parts.size == 2) { "Некорректная запись DC:IP: $line" }
                val dc = parts[0].trim().toIntOrNull()
                    ?: throw IllegalArgumentException("Некорректный номер DC: $line")
                val ip = parts[1].trim()
                require(isIpv4(ip)) { "Некорректный IPv4-адрес: $ip" }
                result[dc] = ip
            }

        return if (result.isEmpty()) ProxyConfig.DEFAULT_DC_IP else result
    }

    fun formatDcIpLines(dcIp: Map<Int, String>): String =
        dcIp.entries.joinToString(separator = "\n") { "${it.key}:${it.value}" }

    private fun isIpv4(ip: String): Boolean {
        val chunks = ip.split(".")
        if (chunks.size != 4) {
            return false
        }
        return chunks.all { part ->
            val value = part.toIntOrNull()
            value != null && value in 0..255 && part == value.toString()
        }
    }
}
