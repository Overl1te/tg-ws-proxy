package com.flowseal.tgwsproxyandroid

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.flowseal.tgwsproxyandroid.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ProxyLogStore.attach(applicationContext)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindInitialConfig()
        bindActions()
        observeState()
        observeLogs()
    }

    private fun bindInitialConfig() {
        val config = ProxyConfigStore.load(this)
        binding.hostInput.setText(config.host)
        binding.portInput.setText(config.port.toString())
        binding.dcIpInput.setText(ProxyConfigStore.formatDcIpLines(config.dcIp))
        binding.verboseSwitch.isChecked = config.verbose
        updateEndpointPreview(config)
    }

    private fun bindActions() {
        binding.saveButton.setOnClickListener {
            val config = currentConfigOrNull() ?: return@setOnClickListener
            ProxyConfigStore.save(this, config)
            updateEndpointPreview(config)
            toast(getString(R.string.toast_config_saved))
        }

        binding.startButton.setOnClickListener {
            val config = currentConfigOrNull() ?: return@setOnClickListener
            maybeRequestNotificationPermission()
            ProxyForegroundService.start(this, config)
            updateEndpointPreview(config)
        }

        binding.stopButton.setOnClickListener {
            ProxyForegroundService.stop(this)
        }

        binding.telegramButton.setOnClickListener {
            val config = currentConfigOrNull() ?: return@setOnClickListener
            maybeRequestNotificationPermission()
            ProxyConfigStore.save(this, config)
            ProxyForegroundService.start(this, config)
            updateEndpointPreview(config)
            openTelegramProxySetup(config)
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ProxyStateStore.state.collectLatest { state ->
                    val text = buildString {
                        append(getStatusText(state.status))
                        if (state.detail.isNotBlank()) {
                            append("  ")
                            append(state.detail)
                        }
                    }
                    binding.stateText.text = text
                    binding.stateText.setBackgroundColor(ContextCompat.getColor(this@MainActivity, statusColor(state.status)))
                }
            }
        }
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ProxyLogStore.lines.collectLatest { lines ->
                    binding.logsText.text = if (lines.isEmpty()) {
                        getString(R.string.logs_placeholder)
                    } else {
                        lines.joinToString(separator = "\n")
                    }
                }
            }
        }
    }

    private fun currentConfigOrNull(): ProxyConfig? {
        val host = binding.hostInput.text?.toString()?.trim().orEmpty()
        if (host.isBlank()) {
            toast(getString(R.string.toast_host_required))
            return null
        }

        val port = binding.portInput.text?.toString()?.trim()?.toIntOrNull()
        if (port == null || port !in 1..65535) {
            toast(getString(R.string.toast_port_invalid))
            return null
        }

        val dcIp = try {
            ProxyConfigStore.parseDcIpLines(
                binding.dcIpInput.text?.toString().orEmpty(),
            )
        } catch (_: IllegalArgumentException) {
            toast(getString(R.string.toast_dc_ip_invalid))
            return null
        }

        return ProxyConfig(
            host = host,
            port = port,
            dcIp = dcIp,
            verbose = binding.verboseSwitch.isChecked,
        )
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            return
        }
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(permission)
        }
    }

    private fun openTelegramProxySetup(config: ProxyConfig) {
        lifecycleScope.launch {
            delay(350)
            val primary = buildTelegramUri(config, telegramScheme = true)
            try {
                startActivity(Intent(Intent.ACTION_VIEW, primary))
            } catch (_: ActivityNotFoundException) {
                toast(getString(R.string.toast_telegram_fallback))
                val fallback = buildTelegramUri(config, telegramScheme = false)
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, fallback))
                } catch (_: ActivityNotFoundException) {
                    toast(getString(R.string.toast_telegram_missing))
                }
            }
        }
    }

    private fun buildTelegramUri(config: ProxyConfig, telegramScheme: Boolean): Uri {
        val builder = if (telegramScheme) {
            Uri.Builder().scheme("tg").authority("socks")
        } else {
            Uri.Builder().scheme("https").authority("t.me").appendPath("socks")
        }
        return builder
            .appendQueryParameter("server", config.host)
            .appendQueryParameter("port", config.port.toString())
            .build()
    }

    private fun updateEndpointPreview(config: ProxyConfig) {
        binding.endpointText.text = "${config.host}:${config.port}"
    }

    private fun statusColor(status: ProxyStatus): Int = when (status) {
        ProxyStatus.RUNNING -> R.color.status_running
        ProxyStatus.STARTING -> R.color.status_starting
        ProxyStatus.STOPPING -> R.color.status_stopping
        ProxyStatus.ERROR -> R.color.status_error
        ProxyStatus.STOPPED -> R.color.status_idle
    }

    private fun getStatusText(status: ProxyStatus): String = when (status) {
        ProxyStatus.RUNNING -> getString(R.string.status_running)
        ProxyStatus.STARTING -> getString(R.string.status_starting)
        ProxyStatus.STOPPING -> getString(R.string.status_stopping)
        ProxyStatus.ERROR -> getString(R.string.status_error)
        ProxyStatus.STOPPED -> getString(R.string.status_stopped)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
