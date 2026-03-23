package com.flowseal.tgwsproxyandroid

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ProxyStatus {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
}

data class ProxyServiceState(
    val status: ProxyStatus = ProxyStatus.STOPPED,
    val detail: String = "",
)

object ProxyStateStore {
    private val _state = MutableStateFlow(ProxyServiceState())
    val state: StateFlow<ProxyServiceState> = _state.asStateFlow()

    fun update(status: ProxyStatus, detail: String = "") {
        _state.value = ProxyServiceState(status = status, detail = detail)
    }
}
