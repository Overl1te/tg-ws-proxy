# TG WS Proxy Android

Android-порт `tg-ws-proxy` на чистом Kotlin. Прокси работает как foreground service и слушает локальный SOCKS5 на `127.0.0.1:1080` по умолчанию.

## Что уже есть

- Android-приложение с базовым UI
- foreground service для фоновой работы
- конфиг `host/port/dc_ip/verbose`
- перенос ядра SOCKS5 + WebSocket bridge в Kotlin

## Что нужно установить

1. Android Studio
2. Android SDK Platform 34
3. Встроенный JDK 17 из Android Studio обычно достаточно, отдельно ставить JDK не обязательно

## Как запустить

1. Открой папку проекта `android` в Android Studio
2. Дождись Gradle Sync
3. Запусти приложение на реальном Android-устройстве
4. В приложении нажми `Start`
5. В Telegram Android настрой SOCKS5-прокси вручную на `127.0.0.1:1080`
