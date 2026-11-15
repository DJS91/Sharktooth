# Sharktooth feature guide

Detailed operation and compatibility notes for the features in the [README](../README.md); see [diagnostics](diagnostics.md) for troubleshooting.

## Features

**Colors** — Set ambient bars (left, middle, right) and DiKey backlight with HSV wheels. Pick modes (solid / breath / blink / flow / off). Day and night profiles switch automatically from the vehicle ambient-light sensor (same signal as auto headlights / tunnels).

**Button mapping** — Down click keeps climate (as printed) unless remapped. Down long, up, and up long can open apps or run Android Intents (activity / broadcast).

**Dials** — Left dial = passenger, right dial = driver. Click to switch between **temp** and **fan**, rotate to adjust. Each dial returns to temperature after 3 seconds without a turn in fan mode.

**Vehicle info** — Live dashboard for battery, range, fuel, driving data, tyre pressures, vehicle attitude, and head-unit resource usage.

**ADAS** — View current driver-assistance settings or save and apply a custom ELKA, LDA, AEB, and DMS profile. Long-press the ADAS tile to configure automatic application when the app starts.

**Sentry** — Install, update, and open the separate [Strike](https://github.com/UnrealSalty/Strike) sentry app.

**Pet Mode** — Keeps the display awake and periodically enforces a configurable climate profile while the vehicle is **READY**. Fan speed, temperature, recirculation, and wind direction can be configured; the previous climate and brightness settings are restored on exit.

**Phone projection** — Open CarPlay or Android Auto from the home tile. In Settings, enable automatic switching to show a cancellable three-second countdown once a phone is connected and any enabled ADAS auto-apply has completed. Automatic switching works while the Sharktooth home screen is visible.

**ESP32 WiFi** — Connect the bridge to a [2.4 GHz network](esp32-wifi.md).

**Home Assistant telemetry** — Optionally send vehicle data via an ESP32 HTTPS webhook; see [telemetry setup and privacy](home-assistant-telemetry.md).

**Camping fridge (experimental)** — Monitor and control compatible [EVAKOOL fridges](camping-fridge.md).

**Background listener** — After you open Sharktooth, a foreground service keeps the USB/BLE DiKey link alive while the UI is in the background. Open the app once after power-on (see [autostart.md](../autostart.md) for DiLink limitations).

**In-app updates** — Home gear icon → **Check for updates** downloads the latest `open-dikey.apk` from [GitHub Releases](https://github.com/DJS91/Open-DiKey/releases).

### DiKey button defaults

| Button | Downward press                |
| ------ | ----------------------------- |
| 1      | Climate on/off                |
| 2      | A/C compressor                |
| 3      | Cycle wind                    |
| 4      | Recirc                        |
| 5      | Auto                          |
| 6      | Front defog                   |
| 7      | Rear window / mirrors         |
| 8      | Air only                      |
| 9      | Sync (driver/passenger temps) |
| 10     | Max cool                      |
