# Soma Watch (`:wear` + `:mobile`)

**Soma Watch** — персональная открытая платформа биометрии, долголетия и самоэволюции для **Samsung Galaxy Watch 8 (Wear OS 6 / Android 16)** и Android-компаньона.

## Архитектура
- **`applicationId`:** `com.aisling.somawatch` (ОБЯЗАТЕЛЬНО одинаковый в `:wear` и `:mobile` для работы Wearable Data Layer API).
- **`:wear`** — Jetpack Compose for Wear OS (`WearMainActivity.kt`), голосовая диктовка задач прямо с запястья (`/soma/voice_prompt`), приём OTA APK по Bluetooth (`WearDataLayerService.kt` → `WearOtaInstaller.kt`).
- **`:mobile`** — Material 3 компаньон (`MobileMainActivity.kt`), создание GitHub Issues с лейблом `jules` (`GitHubJulesClient.kt`), проверка GitHub Releases и пересылка `wear-debug.apk` на часы через `ChannelClient` (`GitHubOtaManager.kt`).
- **`.github/workflows/build-ota.yml`** — при каждом пуше в `main` собирает оба APK с инкрементальным `versionCode`, подписывает общим `debug.keystore` (секрет `DEBUG_KEYSTORE_BASE64`) и публикует в GitHub Releases.

## Правила для агентов (Antigravity Remote / Automation)
1. **Не меняй `applicationId`** (`com.aisling.somawatch`) и `pathPrefix="/soma"` в манифестах.
2. **Не удаляй OTA-модуль и голосовой пайплайн** (`WearDataLayerService`, `WearOtaInstaller`, `GitHubOtaManager`, `GitHubJulesClient`, `GeminiVoiceClient`) — это основа самообновления.
3. **Никогда не коммить секреты и токены** (репозиторий публичный).
4. **Проверка сборки (обязательно перед PR/коммитом):**
   ```bash
   ./gradlew :wear:assembleRelease :mobile:assembleRelease
   ```
5. **Локальная верификация на физических устройствах (Antigravity):**
   Запусти `./scripts/deploy_and_verify.sh` для сборки, установки по Wireless ADB и снятия живого скриншота `watch_screen.png` с экрана Galaxy Watch 8.
