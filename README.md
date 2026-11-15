# <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" alt="" width="48"> Sharktooth

[![GitHub release](https://img.shields.io/github/v/release/DJS91/Open-DiKey?label=release)](https://github.com/DJS91/Open-DiKey/releases/latest)

Sharktooth is an Android app for the BYD Shark 6's DiLink 5 head unit. DiKey buttons and an ESP32 bridge are optional; the app's vehicle features do not require them.

> **Compatibility:** BYD Shark 6. Other vehicles are untested. Not affiliated with BYD, DiKey or their sellers. Use at your own risk.

## Features

- **Vehicle dashboard** — Battery, range, fuel, tyres, driving data and head-unit status.
- **ADAS profiles** — View and apply driver-assistance settings, with optional automatic application.
- **Pet Mode** — Keep the display awake and maintain a configured climate profile while the vehicle is READY.
- **Sentry** — Install, update and launch the separate [Strike](https://github.com/UnrealSalty/Strike) app.
- **CarPlay / Android Auto** — Launch from the home screen or switch automatically when connected.
- **DiKey controls (optional)** — Map buttons, control climate with the dials, and configure button and ambient lighting.
- **[EVAKOOL camping fridge](docs/camping-fridge.md) (experimental)** — Monitor temperatures and voltage, control power and settings, and view history and alerts.
- **[Home Assistant telemetry](docs/home-assistant-telemetry.md)** — Optionally send vehicle, climate, ADAS and location data via an ESP32 HTTPS webhook.
- **[ESP32 WiFi](docs/esp32-wifi.md)** — Connect the bridge to a 2.4 GHz network.
- **Background listener and updates** — Keep the connection alive and check GitHub for new releases.

Compared with upstream Open DiKey, this fork adds fridge integration, telemetry, ESP32 WiFi tools, phone projection and dial fan-mode timeout.

For detailed setup, privacy, troubleshooting and safety information, see the [feature guide](docs/feature-guide.md).

## App installation

1. Enable USB debugging on the DiLink 5 head unit.
2. Download `open-dikey.apk` from the [latest release](https://github.com/DJS91/Open-DiKey/releases), sideload it onto the head unit and open it.
3. Accept **Allow USB debugging** and the hidden-API exemption; the app may restart.
4. Allow Sharktooth in the DiLink **auto-start / run in background** settings if available.

Open Sharktooth once after each cold boot or deep sleep. See [autostart.md](autostart.md) for limitations.

The APK keeps its existing filename and Android package ID for update compatibility. Replacing an installed build also requires a compatible signing key and version.

## Optional DiKey button installation

The DiKey button bar can connect over Bluetooth or through an ESP32-C3 USB bridge. Some head units have limited BLE support; the USB bridge is recommended in that case.

### ESP32 USB bridge

You need a DiKey button bar, an **ESP32-C3 Super Mini** ([AliExpress](https://www.aliexpress.com/item/1005012631245655.html) or [Amazon AU](https://www.amazon.com.au/dp/B0GR475MP4)), and a USB cable.

Flashing the full image can overwrite existing board data; back up anything important first.

1. Download `dikey-usb-bridge-c3.bin` from the [latest release](https://github.com/DJS91/Open-DiKey/releases).
2. On a PC, open [ESPWebTool](https://esptool.spacehuhn.com/) in Chrome or Edge and connect to **USB JTAG/serial debug unit**. If it is not listed, hold **BOOT** while plugging in the board.
3. Delete existing address slots, add one at **`0x0`**, select the downloaded firmware and click **Program**.
4. Plug the bridge into the car's **USB-C port** and grant USB permission. If no prompt appears, unplug and reconnect it while the app is open.

To connect without the bridge, enable head-unit Bluetooth and grant Sharktooth **Nearby devices / Bluetooth** permission.

The ESP32 bridge is also used for WiFi telemetry and the default fridge connection, independently of whether you have a DiKey bar. See the [firmware guide](firmware/README.md) and [feature guide](docs/feature-guide.md) for requirements and limitations.

## For developers

- [Android Kotlin source](app/src/main/java/com/sphy/airconcontroller/) and [XML layouts](app/src/main/res/layout/).
- [ESP32 bridge firmware](firmware/).
- [DiKey protocol notes](assets/Docs/vendor-re/PROTOCOL.md), [offline vendor docs](assets/Docs/Offline/) and [BYD API documentation](assets/BYD%20API/).
- [Diagnostics and troubleshooting](docs/diagnostics.md).

## Credits

Sharktooth is forked from **[Open DiKey](https://github.com/sp-hy/Open-DiKey)** by **Sphy (sp-hy)**. Thank you to Sphy and the Open DiKey contributors for the original app and DiKey integration. The upstream MIT license and copyright notice are preserved in [LICENSE](LICENSE).

DiLink 5 permission and SDK loading follow [BYD Trip Stats](https://github.com/angoikon/byd-trip-stats) by Angelos Oikonomou and contributors. Built with [dadb](https://github.com/mobile-dev-inc/dadb). Vendor manuals are redistributed for offline use with purchased hardware; copyright remains with their owners.

### Icons

- [Wheel](https://thenounproject.com/icon/wheel-8052406/) by Asiah from [Noun Project](https://thenounproject.com/) (CC BY)
- [Color wheel](https://thenounproject.com/icon/color-wheel-8403265/) by Fahad Hashmi from [Noun Project](https://thenounproject.com/) (CC BY)
- [Button](https://thenounproject.com/icon/button-8215836/) by Larea from [Noun Project](https://thenounproject.com/) (CC BY)
- [Dial](https://thenounproject.com/icon/dial-4575189/) by Zach Bogart from [Noun Project](https://thenounproject.com/) (CC BY)
- [adas](https://thenounproject.com/icon/adas-4256808/) by Arijit Dey from [Noun Project](https://thenounproject.com/) (CC BY)
- [recording](https://thenounproject.com/icon/recording-7103165/) by Jonathan Wong from [Noun Project](https://thenounproject.com/) (CC BY)
- [Paw](https://thenounproject.com/icon/paw-626047/) by Rafiico Creative Studio from [Noun Project](https://thenounproject.com/) (CC BY)
