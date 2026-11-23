# Выпуск TractorGram

Стабильные APK и метаданные находятся в [GitHub Releases](https://github.com/mraliscoder/TractorGram/releases). Объявления о выпусках можно читать в [Telegram-канале TractorGram](https://t.me/tractorgraminfo).

## Сборка

База этой версии — Telegram Android [13.2.2 (7303)](https://github.com/TelegramMessenger/Telegram-Android/commit/21f7d109da6ab3506c9e9577f98c6a38771ab335). Стабильный вариант TractorGram — `TMessagesProj_App:bundleAfatRelease`. Идентификатор приложения — `network.zov.messenger`. Вариант поддерживает `arm64-v8a`, `armeabi-v7a`, `x86` и `x86_64`. Для него требуется Android 6.0 (API 23) или новее.

Используйте JDK 17 в CI, Android SDK 36, Build Tools 36.0.0, NDK 27.2.12479018, CMake 3.22.1 и Gradle Wrapper 8.13. На Windows сохраните LF в файлах тем по правилам `.gitattributes`.

```sh
git submodule sync --recursive
git submodule update --init --recursive --depth 1
bash gradlew :TMessagesProj_App:assembleBundleAfatRelease
bash gradlew :TMessagesProj_App:lintBundleAfatRelease :TMessagesProj:testStandaloneUnitTest
```

Gradle читает `TRACTORGRAM_RELEASE_KEYSTORE`, `TRACTORGRAM_RELEASE_STORE_PASSWORD`, `TRACTORGRAM_RELEASE_KEY_ALIAS` и `TRACTORGRAM_RELEASE_KEY_PASSWORD` для подписи стабильного варианта. Файл ключа хранится вне репозитория. GitHub Actions загружает его из `RELEASE_KEYSTORE_BASE64` и загружает остальные параметры подписи из одноимённых секретов без префикса `TRACTORGRAM_`. Локальный ключ из рабочей копии подходит для чистой тестовой установки. Для выпуска нужен ключ с сертификатом опубликованного приложения.

Для проверки пакета используйте метаданные, извлечённые из подписанного APK.

```sh
python3 Tools/tractorgram_release_metadata.py \
  --apk TMessagesProj_App/build/outputs/apk/bundleAfat/release/app.apk \
  --build-tools "$ANDROID_SDK_ROOT/build-tools/36.0.0"
```

При проверке кандидата для публикации добавьте `--commit "$(git rev-parse HEAD)" --output /path/outside/repo/update.json`. Этот режим требует фактический код выше `70381` и сертификат, совпадающий с опубликованной версией.

## Публикация

Push в `tractorgram-changes` запускает сборку и публикацию в `mraliscoder/TractorGram`. Выполняйте его после согласования выпуска. Workflow проверяет подпись, извлекает сведения из `app.apk` и сохраняет APK и `update.json` в артефакте Actions с SHA сборки.

Для GitHub Release используется тег формата `v<versionName>-<versionCode APK>`. Workflow задаёт SHA сборки как цель черновика, сверяет SHA-256 обеих загруженных файлов, публикует выпуск и проверяет SHA удалённого тега и скачанные файлы. Существующий `v12.10.1-7038` остаётся доступным по исходному адресу.

Если публикация остановилась на черновике, проверьте его цель и артефакт конкретного запуска. Повторная отправка того же тега требует проверки черновика или существующего выпуска. Для исправления уже опубликованной сборки выпустите новый APK с большим `versionCode`.

## Первый переход и восстановление

Владельцы 12.10.1 скачивают первый подписанный выпуск с новой проверкой со страницы Releases и устанавливают его поверх приложения. Затем этот клиент предлагает совместимые более свежие выпуски из того же репозитория. В настройках доступны ручная проверка и отключение фоновых проверок. Действие «Позже» скрывает предложение текущей версии на семь дней. Более свежая версия может появиться раньше.

Перед установкой проверьте, что APK имеет тот же пакет и сертификат, больший фактический код и поддерживает Android и архитектуру устройства. На Android 5.x сохранится предыдущая установленная версия. При ошибке подписи или несовместимости остановитесь и оставьте установленное приложение с данными. Удаление приложения стирает локальные данные. После установки сбойной сборки выпустите подписанное исправление с большим кодом, поскольку штатная установка APK поверх приложения с понижением кода запрещена. Проверяйте обратимость изменений локальной БД на отдельном профиле.
