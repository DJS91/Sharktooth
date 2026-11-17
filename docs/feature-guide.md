# Sharktooth feature guide

Detailed operation and compatibility notes for the features in the [README](../README.md); see [diagnostics](diagnostics.md) for troubleshooting.

## Features

The app opens on Dashboard. Use the right-hand sidebar to switch pages; its blue selection highlight slides to the tapped tile with an ease-in/ease-out animation. Cards and sections are borderless in both styles, and floating panel shadows extend into the outer app margins. Settings and Debug remain in the top row. Leaving Pet Mode from the sidebar restores its previous climate and brightness settings.

Classic uses the same cabin-photo background at the same opacity in light and dark mode, visible through the translucent page and sidebar. Modern keeps its solid background.

**Controls Bar** — This sidebar item appears when the DiKey buttons are connected; debug builds always show it for testing. Select it, then use the **Colours** and **Buttons** tabs to switch between lighting and button mapping.

**Colours** — Set ambient bars (left, middle, right) and DiKey backlight. Tap a collapsed summary to expand that zone; the other zones collapse. Matching values line up across the cards. Use the colour bar, saturation, brightness and RGB sliders to adjust the active zone; the marker above the bar shows the selected colour. The page opens on the day or night profile currently shown on the bar, so changes are applied and saved to the colours that remain after leaving. Selecting the other profile previews it on a connected DiKey, with colour updates sent throughout a slider drag rather than only when it stops. Automatic ambient-light switching is paused while this page is open and resumes when you leave. **Sync bar colours** combines the three bar controls, leaving key backlight and each bar's mode independent. The first time sync is enabled, each day/night profile uses its left bar's colour. Turning sync off restores the separate colours; turning it back on restores the last synced colour for each profile. The image above the controls previews the bar and key backlight colours as you edit the day or night profile, with glow reflecting their brightness; it does not animate lighting modes. Its dials show the latest known values on the connected DiKey, and are blank when the display is off or its value is unknown. Debug builds show labelled example dial values while disconnected. Pick modes (solid / breath / blink / flow / off) when sync is off. Day and night profiles switch automatically from the vehicle ambient-light sensor (same signal as auto headlights / tunnels).

**Button mapping** — Down click keeps climate (as printed) unless remapped. Down long, up, and up long can open apps or run Android Intents (activity / broadcast).

The Colours page uses darker segment panels in light mode and a filled **Sync bar colours** button. Bar modes are selected directly from the slim horizontal **Off / Solid / Breath / Blink / Flow** button row beside the **Mode** label; the selected mode is highlighted. Light-mode button labels use dark text. Temperature dial previews use a third seven-segment position with only its upper loop lit as a degree symbol; fan and volume values have no degree symbol, and single-digit values have no leading zero.

**Dials** — Left dial = passenger, right dial = driver. Click to switch between **temp** and **fan**, rotate to adjust. Each dial returns to temperature after 3 seconds without a turn in fan mode.

**Dashboard** — Live cards for battery, range, fuel, driving data, tyre pressures, vehicle attitude, and head-unit resource usage. There is no page heading, leaving more vertical space for the cards.

**ADAS** — View current driver-assistance settings or save and apply a custom ELKA, LDA, AEB, and DMS profile. Long-press the ADAS tile to configure automatic application when the app starts.

**Sentry** — Install, update, and open the separate [Strike](https://github.com/DJS91/Strike) sentry app.

**Modes** — The sidebar opens the Camp Mode page with tabs for **Pet Mode** and **Camp Mode**. Camp Mode activation is disabled until the vehicle's window and display-mode controls are verified; it does not change vehicle settings or brightness.

**Camp Mode interactive tests** — Open Debug (bug icon) → **Camp Mode interactive tests** while safely parked. Capture a baseline, change one setting using the car's controls, select the matching label, optionally describe what you saw, and tap **Capture and compare**. Close the other windows before testing each window separately; capture Light, Dark and Auto display settings separately. Raw window identifiers and SDK constant values are included, but differences do not by themselves validate setter parameters. Captures and the baseline survive leaving the page or restarting the app. **Share test report** exports the local session for review; there is no automatic upload. **Clear test session** deletes the local session without undoing vehicle settings. The confirmed **Test climate OFF** command requires confirmation, records the result and delayed power read-back, and leaves climate off (restore it manually). **Test display dimming** sets the centre display to 10% (never zero) with `BYDAutoSettingDevice.setInfotainmentBrightness` and Android's system brightness (Sharktooth grants itself **Modify system settings** over local ADB, because some head units have no screen for it). **Test screen off** sends the car's own Screen off shortcut command (starts `com.byd.carsettings/com.byd.dialogservice.DialogService` with `byd_pad://byd_car_settings:2935/launcher?action=screen_saver`, falling back to `am startservice` over local ADB); touch the screen to wake it. Dimming restores the previous brightness on touch, loss of foreground or after 10 seconds, and the report lists every command attempt. **Record car screen-off shortcut** reads the head unit's log over local ADB: start it, press the car's own Screen off shortcut, wake the screen and tap **Finish recording and show log**; the matching activity, broadcast, key and power/display log lines are shown on screen and added to the report. **Test close all windows** (car switched on) requires confirmation, sends the all-windows close command (`setAllWindowState(2,2,2,2)`, or `setWindowControlState([2,2,2,2])` on DiLink 5), and records each window's open percentage as it closes. **STOP WINDOWS** stays available during the test and halts all windows immediately. **Test daytime running lights OFF** (car in Park) requires confirmation, sends `BYDAutoLightDevice.setDayTimeLightState(2)` and confirms with the car's DRL read-back, because the car can accept the command without switching; **Restore daytime running lights ON** sends `setDayTimeLightState(1)`. Vehicle-theme command buttons remain locked pending verified mappings. These tests do not activate Camp Mode.

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
