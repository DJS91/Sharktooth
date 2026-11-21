# Diagnostics and troubleshooting

## Engine RPM

The Engine card and Power Flow use `BYDAutoEngineDevice.getEngineSpeed()`, documented in the bundled BYD API V1.0.5 reference, section 6.8.5, as engine speed in rpm with a range of 0–8,000. Out-of-range readings are shown as unavailable, not converted to zero, and do not activate the engine flow.

If RPM is unavailable or appears stuck, the vehicle information dump includes the raw `Engine.getEngineSpeed` result. A read-only comparison on the head unit with the engine stopped and running is needed to confirm this firmware's signal; EV mode alone is not used to invent an RPM reading.

## Camp Mode API inventory

On the head unit, open **Debug → Diagnostics report → Run diagnostics** (not the climate write test). The **Camp Mode API inventory** section lists relevant public window and display-mode SDK method signatures and constant names without invoking them. This is discovery information, not proof that a command is safe or supported. Share only that section when possible; the full report may include vehicle identifiers and device addresses.

## Save SoC switch

The target and switch are separate vehicle readings. If the switch status is unavailable, the controls stay disabled rather than guessing a vehicle setting. Run the read-only diagnostics report with Save SoC off and again with it on, and compare the **Save SoC API inventory** and the `Charging.getSocSaveSwitch` line in **Vehicle info**. The inventory lists matching SDK signatures and constant names without calling them; it does not establish which command is safe or supported. Share only those excerpts after removing any sensitive information.

## Upload diagnostics

**Debug → Run and upload diagnostics** runs a fresh diagnostic report and uploads it using the head unit's internet connection to the third-party service aisenseapi.com, after confirmation. The report includes up to 400 recent DiKey and USB log lines captured since the app process started. WiFi credential commands are redacted from the USB trace; other diagnostic output can still contain vehicle identifiers, device addresses or other sensitive information. The returned link is shown on screen with a QR code you can scan with your phone to open the report. The QR code is generated locally and clears when a new run starts. Anyone who has the link or QR code can access the report, and the service states that it expires after 24 hours. Treat both as private.

## Bluetooth scan

**Run BLE scan (15s)** counts raw advertisement callbacks *before* fridge-name/connectability filters, shows elapsed time, sample names/addresses/RSSI/service UUIDs and Android scan error codes, and distinguishes a scan request from confirmed reception. A request returning without exception is not proof of radio activity; zero callbacks is inconclusive, while any callback confirms BLE reception. The test stops when leaving the page and never disconnects DiKey, connects to a peripheral or sends commands. Wait 30 seconds between scan starts to avoid throttling. Use **Copy** or **Share** to export the report; review nearby device identities before sharing. Third-party BLE restrictions or inaccurate feature reporting in BYD firmware may still need ADB investigation.

See the [camping fridge guide](camping-fridge.md#diagnostics) and [ESP32 WiFi guide](esp32-wifi.md) for device-specific diagnostics.
