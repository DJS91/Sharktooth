# Home Assistant telemetry

In Settings, enter a publicly trusted HTTPS webhook URL, choose an interval (5–3600 seconds; default 5), enable telemetry and save. With the app's foreground listener running and the ESP32 connected over USB and WiFi, sending begins immediately after WiFi is confirmed connected and repeats after each completed attempt. Settings shows the most recent result.

WiFi must pass the Google DNS check described in the [ESP32 WiFi guide](esp32-wifi.md); the ESP32 also needs NTP time and a valid publicly trusted server certificate. Private/self-signed certificates, HTTP and redirects are not supported. Update both app and firmware; the HTTPS firmware needs a changed flash partition table, so flash its complete merged image at `0x0` rather than only the app partition. The new partition layout can overwrite existing data in the affected flash areas; back up anything important first.

## Privacy

Telemetry is opt-in. The URL is stored unencrypted in the app's private preferences and sent over USB; treat it as a secret. The JSON includes vehicle identifiers such as VIN when available, so use only a webhook you trust. Optional location requires permission and sends the most recent location already known to Android, including its timestamp and accuracy; it can be stale or unavailable, especially in the background. Disabling telemetry prevents further scheduled sends; a request already handed to the ESP32 may still finish.

## Payload

Payload schema version 1 has `schema_version`, UTC `timestamp`, `adas`, `climate`, `vehicle` and `location` objects. `vehicle` contains `battery`, `dynamics`, `tyres` and `devices_bound`. Temperature keys ending in `_c` are Celsius; other measured units appear in their keys, and `_raw` values retain OEM encodings. Unavailable readings are JSON null. `location.status` distinguishes disabled, denied or unavailable readings from `last_known`; the location timestamp is the fix time, not the transmission time. Climate readings can include the app's last requested values where the vehicle API does not expose a live getter.
