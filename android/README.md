# Synchro · Android development

Нативное приложение на Java: LibVLC для локального видео, официальный iframe для YouTube, системный PiP, обмен текстом/фото и вкладками. Основной Java namespace — `dev.synchro.app`. Установочный `applicationId` `dev.potplayer.handoff` сохранён для обновления существующих установок; он не является названием продукта.

## Сборка

JDK 21, Gradle 8.11.1, Android SDK 36 / Build Tools 35.0.0. Минимум Android 8.0 (API 26), ARM64 / x86-64.

```sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

На Windows из корня проекта:

```powershell
./android/build-local.ps1 -JavaHome '<JDK 21>' ':app:testDebugUnitTest' ':app:lintDebug' ':app:assembleDebug'
./android/verify-apk.ps1 -JavaHome '<JDK 21>'
```

Bootstrap загружает официальные SDK/Gradle с проверкой SHA-256 в `.tools`, не меняя пользовательское окружение. APK находятся в `android/app/build/outputs/apk/debug/`: universal, arm64-v8a и x86_64. Debug APK нельзя установить поверх release APK с другим сертификатом.

Release использует `SYNCHRO_KEYSTORE`, `SYNCHRO_STORE_PASSWORD`, `SYNCHRO_KEY_ALIAS`, `SYNCHRO_KEY_PASSWORD`. Ключ постоянный, в репозитории его нет. При новой версии увеличивайте `versionCode` и `versionName`. [Релизы и OTA](../docs/RELEASING.md).

## Слои

- `ApiClient`, `SessionState`, `ProgressUpdate`: ограниченный HTTP, проверка входных данных и упорядоченные снимки состояния.
- `PlaybackController`: единственный владелец LibVLC на главном потоке; очередь, дорожки и задержка субтитров.
- `MainActivity`, `ReceiverUi`, `AppDesign`, `SourceSheet`: подключение, выбранный источник, навигация и управление просмотром.
- `PipController`: локальное видео в системном Picture-in-Picture, действия Play/Pause и checkpoint при закрытии.
- `YouTubeActivity`: официальный встроенный плеер без native JavaScript interface; ключ подключения остаётся в Java.
- `SyncActivity`, `ClipboardImages`, `ClipboardImageReceiver`: ручной обмен и получение изображения фоновым ADB-получателем.
- `UpdateActivity`, `GitHubRelease`: стабильные релизы, SHA-256, проверка версии/пакета/сертификата перед системной установкой.

## Проверки

JVM-тесты выполняются обычным Gradle. Нативный runner — `dev.potplayer.handoff.test/dev.synchro.app.PlaybackLatencyInstrumentation`. Он работает только с изолированным сервером на порту 18765 и собственными синтетическими файлами. Нельзя направлять его на рабочую сессию или реальный буфер пользователя.

Наборы `design`, `pip`, `sharing`, `clipboardImages`, `updates` проверяют настоящие Android Activities и системные API. По умолчанию выполняется сценарий Play/Pause с намеренно медленным тестовым ПК. Для `clipboardImages` дополнительно нужен `imagesFixture=synthetic-emulator-only`; он меняет буфер только выделенного эмулятора. [Результаты](../VALIDATION.md), [протокол паузы](../docs/LATENCY.md).

## Границы

Исходные локальные файлы передаются без транскодирования; аппаратное декодирование зависит от устройства и кодека. Переносятся встроенные дорожки, внешние subtitle-файлы пока не передаются. Громкость остаётся локальной. Возможности возврата настроек определяет выбранный ПК-плеер.

YouTube требует доступа к официальному плееру и разрешённого встраивания ролика. При уходе с его экрана приложение ставит видео на паузу; PiP используется для локального видео. [Коннектор](../docs/YOUTUBE_CONNECTOR.md).

LibVLC 3.7.7 проходит ZIP/ELF PT_LOAD alignment 16 КБ, но пять из шести native-библиотек не проходят дополнительную проверку конца GNU_RELRO на границу 16 КБ. Полная совместимость со всеми такими устройствами не заявляется. `verify-apk.ps1 -StrictRelro` фиксирует это как ошибку; бинарные библиотеки не исправлялись вручную.

[Звук](../docs/AUDIO.md) · [Подключение](../docs/PAIRING.md) · [Лицензии](../THIRD_PARTY_NOTICES.md)
