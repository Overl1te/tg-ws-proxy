package com.flowseal.tgwsproxyandroid

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
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
    private var currentStatus: ProxyStatus = ProxyStatus.STOPPED
    private var currentThemeMode: AppThemeMode = AppThemeMode.SYSTEM

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ProxyLogStore.attach(applicationContext)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindInitialConfig()
        bindUiPreferences()
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

    private fun bindUiPreferences() {
        val uiPreferences = UiPreferencesStore.load(this)
        currentThemeMode = uiPreferences.themeMode
        binding.themeChipGroup.check(themeChipId(uiPreferences.themeMode))
        updateLogsVisibility(uiPreferences.logsExpanded)
    }

    private fun bindActions() {
        binding.saveButton.setOnClickListener {
            val config = currentConfigOrNull() ?: return@setOnClickListener
            ProxyConfigStore.save(this, config)
            updateEndpointPreview(config)
            toast(getString(R.string.toast_config_saved))
        }

        binding.powerButton.setOnClickListener {
            when (currentStatus) {
                ProxyStatus.RUNNING -> ProxyForegroundService.stop(this)
                ProxyStatus.STARTING, ProxyStatus.STOPPING -> Unit
                ProxyStatus.STOPPED, ProxyStatus.ERROR -> {
                    val config = currentConfigOrNull() ?: return@setOnClickListener
                    ProxyConfigStore.save(this, config)
                    maybeRequestNotificationPermission()
                    ProxyForegroundService.start(this, config)
                    updateEndpointPreview(config)
                }
            }
        }

        binding.telegramButton.setOnClickListener {
            val config = currentConfigOrNull() ?: return@setOnClickListener
            maybeRequestNotificationPermission()
            ProxyConfigStore.save(this, config)
            ProxyForegroundService.start(this, config)
            updateEndpointPreview(config)
            openTelegramProxySetup(config)
        }

        binding.toggleLogsButton.setOnClickListener {
            val expanded = binding.logsContentContainer.visibility != View.VISIBLE
            UiPreferencesStore.saveLogsExpanded(this, expanded)
            updateLogsVisibility(expanded)
        }

        binding.themeChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val nextThemeMode = when (checkedId) {
                R.id.themeLightChip -> AppThemeMode.LIGHT
                R.id.themeDarkChip -> AppThemeMode.DARK
                else -> AppThemeMode.SYSTEM
            }
            if (nextThemeMode == currentThemeMode) {
                return@setOnCheckedStateChangeListener
            }
            currentThemeMode = nextThemeMode
            UiPreferencesStore.saveThemeMode(this, nextThemeMode)
            UiPreferencesStore.applyThemeMode(nextThemeMode)
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ProxyStateStore.state.collectLatest { state ->
                    currentStatus = state.status
                    binding.stateText.text = getStatusText(state.status)
                    binding.statusChip.setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, statusColor(state.status)))
                    binding.heroCard.setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, heroCardColor(state.status)))
                    binding.heroTitleText.text = getHeroTitle(state.status)
                    binding.heroSubtitleText.text = if (state.status == ProxyStatus.ERROR && state.detail.isNotBlank()) {
                        state.detail
                    } else {
                        getHeroSubtitle(state.status)
                    }
                    binding.powerButton.text = getPowerButtonText(state.status)
                    binding.powerButton.isEnabled = state.status != ProxyStatus.STARTING && state.status != ProxyStatus.STOPPING
                    binding.powerButton.alpha = if (binding.powerButton.isEnabled) 1f else 0.78f
                    binding.powerButton.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this@MainActivity, powerButtonColor(state.status)),
                    )
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

    private fun updateLogsVisibility(expanded: Boolean) {
        binding.logsContentContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.toggleLogsButton.text = if (expanded) {
            getString(R.string.action_hide_logs)
        } else {
            getString(R.string.action_show_logs)
        }
    }

    private fun themeChipId(themeMode: AppThemeMode): Int = when (themeMode) {
        AppThemeMode.SYSTEM -> R.id.themeSystemChip
        AppThemeMode.LIGHT -> R.id.themeLightChip
        AppThemeMode.DARK -> R.id.themeDarkChip
    }

    private fun powerButtonColor(status: ProxyStatus): Int = when (status) {
        ProxyStatus.RUNNING -> R.color.power_button_running
        ProxyStatus.STARTING, ProxyStatus.STOPPING -> R.color.power_button_busy
        ProxyStatus.ERROR -> R.color.power_button_error
        ProxyStatus.STOPPED -> R.color.power_button_off
    }

    private fun heroCardColor(status: ProxyStatus): Int = when (status) {
        ProxyStatus.RUNNING -> R.color.hero_background_running
        ProxyStatus.STARTING, ProxyStatus.STOPPING -> R.color.hero_background_busy
        ProxyStatus.ERROR -> R.color.hero_background_error
        ProxyStatus.STOPPED -> R.color.hero_background
    }

    private fun statusColor(status: ProxyStatus): Int = when (status) {
        ProxyStatus.RUNNING -> R.color.status_running
        ProxyStatus.STARTING -> R.color.status_starting
        ProxyStatus.STOPPING -> R.color.status_stopping
        ProxyStatus.ERROR -> R.color.status_error
        ProxyStatus.STOPPED -> R.color.status_idle
    }

    private fun getPowerButtonText(status: ProxyStatus): String = when (status) {
        ProxyStatus.RUNNING -> getString(R.string.power_button_stop)
        ProxyStatus.STARTING, ProxyStatus.STOPPING -> getString(R.string.power_button_busy)
        ProxyStatus.ERROR, ProxyStatus.STOPPED -> getString(R.string.power_button_start)
    }

    private fun getHeroTitle(status: ProxyStatus): String = when (status) {
        ProxyStatus.RUNNING -> getString(R.string.hero_title_running)
        ProxyStatus.STARTING -> getString(R.string.hero_title_starting)
        ProxyStatus.STOPPING -> getString(R.string.hero_title_stopping)
        ProxyStatus.ERROR -> getString(R.string.hero_title_error)
        ProxyStatus.STOPPED -> getString(R.string.hero_title_stopped)
    }

    private fun getHeroSubtitle(status: ProxyStatus): String = when (status) {
        ProxyStatus.RUNNING -> getString(R.string.hero_subtitle_running)
        ProxyStatus.STARTING -> getString(R.string.hero_subtitle_starting)
        ProxyStatus.STOPPING -> getString(R.string.hero_subtitle_stopping)
        ProxyStatus.ERROR -> getString(R.string.hero_subtitle_error)
        ProxyStatus.STOPPED -> getString(R.string.hero_subtitle_stopped)
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
