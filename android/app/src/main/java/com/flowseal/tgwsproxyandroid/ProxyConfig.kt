package com.flowseal.tgwsproxyandroid

data class ProxyConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val dcIp: Map<Int, String> = DEFAULT_DC_IP,
    val verbose: Boolean = false,
    val bufferKb: Int = DEFAULT_BUFFER_KB,
    val poolSize: Int = DEFAULT_POOL_SIZE,
) {
    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 1080
        const val DEFAULT_BUFFER_KB = 256
        const val DEFAULT_POOL_SIZE = 4

        val DEFAULT_DC_IP: Map<Int, String> = linkedMapOf(
            2 to "149.154.167.220",
            4 to "149.154.167.220",
        )
    }
}
