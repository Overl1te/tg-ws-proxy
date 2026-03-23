package com.flowseal.tgwsproxyandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.flowseal.tgwsproxyandroid.core.TelegramProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ProxyForegroundService : Service() {
    private val serviceScope = CoroutineScope(Job() + Dispatchers.IO)

    private var serverJob: Job? = null
    private var server: TelegramProxyServer? = null

    override fun onCreate() {
        super.onCreate()
        ProxyLogStore.attach(applicationContext)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopProxyAsync()
            else -> startProxyIfNeeded()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runBlocking {
            server?.stop()
            serverJob?.cancelAndJoin()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startProxyIfNeeded() {
        if (serverJob?.isActive == true) {
            ProxyLogStore.info("service", "Запуск запрошен, но прокси уже активен")
            return
        }

        val config = ProxyConfigStore.load(this)
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_text_starting)))
        ProxyStateStore.update(ProxyStatus.STARTING, "${config.host}:${config.port}")
        ProxyLogStore.info("service", "Запуск прокси на ${config.host}:${config.port}")

        serverJob = serviceScope.launch {
            val instance = TelegramProxyServer(
                config = config,
                onStarted = {
                    ProxyStateStore.update(ProxyStatus.RUNNING, "${config.host}:${config.port}")
                    updateNotification(
                        getString(
                            R.string.notification_text_running,
                            config.host,
                            config.port,
                        ),
                    )
                },
                onInfo = { source, message -> ProxyLogStore.info(source, message) },
                onWarn = { source, message -> ProxyLogStore.warn(source, message) },
                onError = { source, message, throwable -> ProxyLogStore.error(source, message, throwable) },
                onDebug = { source, message -> ProxyLogStore.debug(config.verbose, source, message) },
            )

            server = instance

            try {
                instance.run()
                if (ProxyStateStore.state.value.status != ProxyStatus.STOPPING) {
                    ProxyStateStore.update(ProxyStatus.STOPPED)
                }
            } catch (t: Throwable) {
                ProxyStateStore.update(
                    ProxyStatus.ERROR,
                    t.message ?: t::class.java.simpleName,
                )
                ProxyLogStore.error("service", "Прокси завершился с ошибкой", t)
            } finally {
                server = null
                serverJob = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopProxyAsync() {
        val activeServer = server
        val activeJob = serverJob

        if (activeServer == null || activeJob == null) {
            ProxyStateStore.update(ProxyStatus.STOPPED)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        ProxyStateStore.update(ProxyStatus.STOPPING)
        ProxyLogStore.info("service", "Остановка прокси")

        serviceScope.launch {
            runCatching { activeServer.stop() }
            runCatching { activeJob.cancelAndJoin() }
            ProxyStateStore.update(ProxyStatus.STOPPED)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ProxyForegroundService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(
                0,
                getString(R.string.notification_action_stop),
                stopIntent,
            )
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    companion object {
        private const val CHANNEL_ID = "tg_ws_proxy_android"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.flowseal.tgwsproxyandroid.START"
        private const val ACTION_STOP = "com.flowseal.tgwsproxyandroid.STOP"

        fun start(context: Context, config: ProxyConfig) {
            ProxyConfigStore.save(context, config)
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
