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
- [x] **Stage 1:** Multi-module Wear OS + Android companion, Wireless ADB auto-deploy & screenshot verification, Voice-to-Jules GitHub Issue trigger, Bluetooth OTA updater & GitHub Actions CI/CD.
- [ ] **Stage 2:** Live Galaxy Watch 8 sensors (HR, raw IBI/HRV via Samsung Health Sensor SDK), always-on non-minimizing Running/Workout mode (`Ongoing Activity` + `AmbientAware`), live HR graph, automatic Heart Rate Recovery (`HRR-1` / `HRR-2`), and weekly Zone 2 / Zone 4–5 cardio budget (with Watch Face Complications & Tiles).
- [ ] **Stage 3:** Battery-smart duty-cycled decibel meter with vibration alerts, automatic strength workout detection, Health Connect sleep sync, and clean data export (JSON/SQLite/API) for personal AI health coaches.
