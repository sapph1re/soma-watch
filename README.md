# Soma Watch

Open-source, self-evolving biometrics & longevity companion for **Samsung Galaxy Watch 8 (Wear OS)** and Android.

## Vision
**Soma Watch** combines real-time health telemetry with a **voice-driven cloud AI development loop**:
1. Tap **🎙️ Идея для агента** right on your wrist (or phone) and dictate a new metric, widget, or alert.
2. The companion app creates a GitHub Issue labeled `jules`.
3. **Google Jules** implements the feature in the cloud and pushes to `main`.
4. **GitHub Actions** builds fresh `wear-debug.apk` and `mobile-debug.apk` signed with a shared keystore and publishes a GitHub Release.
5. The companion app downloads the release and pushes the new watch APK directly to Galaxy Watch 8 over Bluetooth (**Wearable Data Layer `ChannelClient`** + `PackageInstaller`).

## Roadmap
- [x] **Stage 1:** Multi-module Wear OS + Android companion, Wireless ADB auto-deploy, direct Voice-to-Gemini-to-GitHub-Issue (`soma-agent`), R8-minified Bluetooth OTA updater (~2 MB) with radial bezel progress ring, and GitHub Actions CI/CD.
- [x] **Stage 2:** Live Galaxy Watch 8 sensors (`TYPE_HEART_RATE` + `Samsung HR Raw` IBI/HRV RMSSD), always-on non-minimizing Running/Workout mode (`OngoingActivity` + `FLAG_KEEP_SCREEN_ON`), live HR sparkline graph with Zone 2 band, automatic `HRR-1` / `HRR-2` cooldown timer, weekly Zone 2 / Zone 4–5 cardio budget, Watch Face Complication (`Soma Zone 2`), and smart auto-hiding OTA button.
- [x] **Stage 3:** Battery-smart duty-cycled decibel meter (`SomaNoiseMonitor`) with sustained-noise vibration alert, automatic dynamic/strength workout detection + passive background HRR, Health Connect 30-day sleep/HR/HRV sync, longitudinal SQLite DB (`SomaHealthDb`), JSON Share export, and local Wi-Fi HTTP API (`:8787/api/health` & `/api/summary.md`) + `scripts/pull_health_for_claude.sh` for Claude Code.
