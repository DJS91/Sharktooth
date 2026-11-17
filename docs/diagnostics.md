# Diagnostics and troubleshooting

## Camp Mode API inventory

On the head unit, open **Debug → Diagnostics report → Run diagnostics** (not the climate write test). The **Camp Mode API inventory** section lists relevant public window and display-mode SDK method signatures and constant names without invoking them. This is discovery information, not proof that a command is safe or supported. Share only that section when possible; the full report may include vehicle identifiers and device addresses.

## Upload diagnostics

**Debug → Run and upload diagnostics** runs a fresh diagnostic report and uploads it using the head unit's internet connection to the third-party service aisenseapi.com, after confirmation. The report includes up to 400 recent DiKey and USB log lines captured since the app process started. WiFi credential commands are redacted from the USB trace; other diagnostic output can still contain vehicle identifiers, device addresses or other sensitive information. The returned link is shown on screen: anyone who has it can access the report, and the service states that it expires after 24 hours. Treat the link as private.

## Bluetooth scan

**Run BLE scan (15s)** counts raw advertisement callbacks *before* fridge-name/connectability filters, shows elapsed time, sample names/addresses/RSSI/service UUIDs and Android scan error codes, and distinguishes a scan request from confirmed reception. A request returning without exception is not proof of radio activity; zero callbacks is inconclusive, while any callback confirms BLE reception. The test stops when leaving the page and never disconnects DiKey, connects to a peripheral or sends commands. Wait 30 seconds between scan starts to avoid throttling. Use **Copy** or **Share** to export the report; review nearby device identities before sharing. Third-party BLE restrictions or inaccurate feature reporting in BYD firmware may still need ADB investigation.

See the [camping fridge guide](camping-fridge.md#diagnostics) and [ESP32 WiFi guide](esp32-wifi.md) for device-specific diagnostics.
