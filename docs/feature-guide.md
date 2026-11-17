# Sharktooth feature guide

Detailed operation and compatibility notes for the features in the [README](../README.md); see [diagnostics](diagnostics.md) for troubleshooting.

## Features

The app opens on Vehicle info. Use the right-hand sidebar to switch pages; its blue selection highlight slides to the tapped tile with an ease-in/ease-out animation. Settings and Debug remain in the top row. Leaving Pet Mode from the sidebar restores its previous climate and brightness settings.

**Controls Bar** — This sidebar item appears when the DiKey buttons are connected; debug builds always show it for testing. Select it, then use the **Colours** and **Buttons** tabs to switch between lighting and button mapping.

**Colours** — Set ambient bars (left, middle, right) and DiKey backlight. Tap a collapsed summary to expand that zone; the other zones collapse. Matching values line up across the cards. Use the colour bar, saturation, brightness and RGB sliders to adjust the active zone; the marker above the bar shows the selected colour. **Sync bar colours** combines the three bar controls, leaving key backlight and each bar's mode independent. The first time sync is enabled, each day/night profile uses its left bar's colour. Turning sync off restores the separate colours; turning it back on restores the last synced colour for each profile. The image above the controls previews the bar and key backlight colours as you edit the day or night profile, with glow reflecting their brightness; it does not animate lighting modes. Its dials show the latest known values on the connected DiKey, and are blank when the display is off or its value is unknown. Debug builds show labelled example dial values while disconnected. Pick modes (solid / breath / blink / flow / off) when sync is off. Day and night profiles switch automatically from the vehicle ambient-light sensor (same signal as auto headlights / tunnels).

**Button mapping** — Down click keeps climate (as printed) unless remapped. Down long, up, and up long can open apps or run Android Intents (activity / broadcast).

**Dials** — Left dial = passenger, right dial = driver. Click to switch between **temp** and **fan**, rotate to adjust. Each dial returns to temperature after 3 seconds without a turn in fan mode.

**Vehicle info** — Live dashboard for battery, range, fuel, driving data, tyre pressures, vehicle attitude, and head-unit resource usage.

**ADAS** — View current driver-assistance settings or save and apply a custom ELKA, LDA, AEB, and DMS profile. Long-press the ADAS tile to configure automatic application when the app starts.

**Sentry** — Install, update, and open the separate [Strike](https://github.com/DJS91/Strike) sentry app.

**Modes** — The sidebar opens the Camp Mode page with tabs for **Pet Mode** and **Camp Mode**. Camp Mode activation is disabled until the vehicle's window and display-mode controls are verified; it does not change vehicle settings or brightness.

**Pet Mode** — Select the Pet Mode tab to keep the display awake and periodically enforce a configurable climate profile while the vehicle is **READY**. Fan speed, temperature, recirculation, and wind direction can be configured; the previous climate and brightness settings are restored on exit.

**Phone projection** — Open CarPlay or Android Auto from the shortcut at the bottom of the right-hand sidebar when a phone is projecting. In Settings, enable automatic switching to show a cancellable three-second countdown once a phone is connected and any enabled ADAS auto-apply has completed. Automatic switching works while the vehicle information screen is visible.

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
