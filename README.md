# <img src="assets/sharktooth-logo.svg" alt="" width="48"> Sharktooth

**Compatibility:** BYD Shark 6. Other vehicles are untested.
**Disclaimer:** Not affiliated with BYD, DiKey or their sellers.

[![GitHub release](https://img.shields.io/github/v/release/DJS91/Open-DiKey?label=release)](https://github.com/DJS91/Open-DiKey/releases/latest)

Sharktooth is an open source and free app for the BYD Shark 6's head unit to display and control various vehicle onboard telemetary, statistics and controls and much more! It supports optional DiKey physical buttons for more control. Is a work-in-progress and activly developed.

## Features

- **Vehicle dashboard** — Battery, range, fuel, tyres, driving data and head-unit status. **ESP32: not required.**
- **ADAS profiles** — View and apply driver-assistance settings, with optional automatic application. **ESP32: not required.**
- **Pet Mode** — Keep the display awake and maintain a configured climate profile while the vehicle is READY. **ESP32: not required.**
- **Sentry** — Install, update and launch the separate [Strike](https://github.com/DJS91/Strike) app. **ESP32: not required.**
- **CarPlay / Android Auto** — Launch from the home screen or switch automatically when connected. **ESP32: not required.**
- **DiKey controls (optional)** — Map buttons, control climate with the dials, and configure button and ambient lighting. **ESP32: optional**; direct Bluetooth requires compatible head-unit BLE.
- **[EVAKOOL camping fridge](docs/camping-fridge.md)** — Monitor temperatures and voltage, control power and settings, and view history and alerts. **ESP32: optional**; the default uses the bridge, with a direct Android Bluetooth backup.
- **[ESP32 WiFi](docs/esp32-wifi.md)** — Connect the bridge to a 2.4 GHz network. **ESP32: required.**
- **[Home Assistant telemetry integration](docs/home-assistant-telemetry.md)** — Optionally send vehicle, climate, ADAS and location data via HTTPS. **ESP32: required** via USB with WiFi connected.
- **Background listener and updates** — Keep the connection alive and check GitHub for new releases. **ESP32: not required**, except when using a bridge connection.

Compared with upstream Open DiKey, this fork adds fridge integration, telemetry, ESP32 WiFi tools, phone projection and dial fan-mode timeout.

For detailed setup, privacy, troubleshooting and safety information, see the [feature guide](docs/feature-guide.md).

## App installation

1. Enable USB debugging on the DiLink 5 head unit.
2. Download `open-dikey.apk` from the [latest release](https://github.com/DJS91/Open-DiKey/releases), sideload it onto the head unit and open it.
3. Accept **Allow USB debugging** and the hidden-API exemption; the app may restart.
4. Allow Sharktooth in the DiLink **auto-start / run in background** settings if available.

The APK keeps its existing filename and Android package ID for update compatibility. Replacing an installed build also requires a compatible signing key and version.


### (Optional) Physical Control buttons bar

The DiKey button bar connects to the app through an ESP32-C3 USB bridge via custom firmware.

You need a DiKey button bar, an **ESP32-C3 Super Mini** ([AliExpress](https://www.aliexpress.com/item/1005012631245655.html) or [Amazon AU](https://www.amazon.com.au/dp/B0GR475MP4)), and a USB cable.

Setting up the USB Bridge:
1. Download `dikey-usb-bridge-c3.bin` from the [latest release](https://github.com/DJS91/Open-DiKey/releases).
2. On a PC, open [ESPWebTool](https://esptool.spacehuhn.com/) in Chrome or Edge and connect to **USB JTAG/serial debug unit**. If it is not listed, hold **BOOT** button while plugging in the board and try again.
3. Delete any existing address slots, add one at **`0x0`**, select the downloaded firmware and click **Program**.
4. Plug the esp32 bridge into the car's **USB-C port** and grant USB permission if prompted.

Setting up the buttons:
1. Mount the button bar where you like under the screen
2. Plug in a usb cable (its for power only) into one of the three available ports on the button bar and run the cable to an available usb port in the cabin.

The esp32 bridge will automatically connect to the buttons when the car starts and is ready to go.


The ESP32 bridge is also used for WiFi telemetry and the fridge connection feature, independently of whether you have a physical buttons bar. See the [firmware guide](firmware/README.md) and [feature guide](docs/feature-guide.md) for requirements and limitations.

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
