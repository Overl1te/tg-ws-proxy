package com.flowseal.tgwsproxyandroid

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ProxyLogStore {
    private const val TAG = "TgWsProxyAndroid"
    private const val MAX_LINES = 300

    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val lock = Any()
    private val _lines = MutableStateFlow<List<String>>(emptyList())

    private var logFile: File? = null

    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun attach(context: Context) {
        synchronized(lock) {
            if (logFile != null) {
                return
            }
            val dir = File(context.filesDir, "logs")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            logFile = File(dir, "proxy.log")
            _lines.value = tail(logFile!!, MAX_LINES)
        }
    }

    fun info(source: String, message: String) = log("I", source, message)

    fun warn(source: String, message: String) = log("W", source, message)

    fun error(source: String, message: String, throwable: Throwable? = null) {
        val suffix = throwable?.let { " (${it::class.java.simpleName}: ${it.message.orEmpty()})" }.orEmpty()
        log("E", source, message + suffix)
    }

    fun debug(enabled: Boolean, source: String, message: String) {
        if (enabled) {
            log("D", source, message)
        }
    }

    fun logPath(): String? = synchronized(lock) { logFile?.absolutePath }

    private fun log(level: String, source: String, message: String) {
        val line = "${formatter.format(Date())} $level/$source $message"
        synchronized(lock) {
            logFile?.appendText(line + "\n")
            val next = (_lines.value + line).takeLast(MAX_LINES)
            _lines.value = next
        }

        when (level) {
            "E" -> Log.e(TAG, line)
            "W" -> Log.w(TAG, line)
            "D" -> Log.d(TAG, line)
            else -> Log.i(TAG, line)
        }
    }

    private fun tail(file: File, maxLines: Int): List<String> {
        if (!file.exists()) {
            return emptyList()
        }
        return file.readLines().takeLast(maxLines)
    }
}
