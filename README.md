# Синхро · Android

Нативное Android-приложение: просмотр исходного видео с ПК, системный Picture-in-Picture, буфер обмена и ссылки между Opera и Chrome.

[Скачать APK](https://github.com/giveuper39/syncro-android/releases/latest) · [Windows-сервис и QR-мастер](https://github.com/giveuper39/syncro-desktop)

Сначала запустите Windows-мастер. Для фонового буфера сопрягите телефон системным QR-сканером беспроводной отладки; для видео и ручного обмена достаточно обычного QR приложения. Громкость — штатными кнопками телефона.

Обновления: кнопка «Обновления Синхро» проверяет стабильный тег GitHub, скачивает APK, проверяет digest, package ID, возрастающую версию и постоянную подпись. Установку подтверждает Android.

## Сборка

JDK 21, Gradle 8.11.1, Android SDK 36 и build-tools 35.0.0. Из android/:

```text
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

На Windows: android/build-local.ps1 -JavaHome <JDK>. Скрипт скачивает проверенные SDK/Gradle локально. Подпись release задаётся переменными SYNCRO_KEYSTORE, SYNCRO_STORE_PASSWORD, SYNCRO_KEY_ALIAS, SYNCRO_KEY_PASSWORD. Ключи в исходники не входят.

Тег vMAJOR.MINOR.PATCH запускает подписанную сборку и публикацию Release. См. [выпуск версий](docs/RELEASING.md). UI, воспроизведение, PiP, синхронизация и OTA разделены на отдельные классы. Стабильный package ID сохранён от раннего прототипа для совместимости.

Поддерживаются ARM64 и x86-64, Android 8+. Другие плееры и YouTube — следующий этап. Исходное приложение MIT; зависимости и ограничения LibVLC описаны в [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), результаты — [VALIDATION.md](VALIDATION.md).
