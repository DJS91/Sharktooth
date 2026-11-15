# ESP32 WiFi

In Settings, scan for visible 2.4 GHz networks, select one and enter its password (leave it empty for an open network). Hidden networks and 5 GHz-only networks are not available in the scan. The app stores the password in its private preferences without encryption and sends it to the bridge over USB.

The home screen shows **Wifi Connected** only after the bridge can reach Google DNS on TCP port 53; networks that block that test will not show connected. If it fails, open **Debug → ESP32 WiFi diagnostics** for the reported disconnect reason and test results. Update the app and flash the matching bridge firmware to use scanning and detailed diagnostics.

See the [firmware guide](../firmware/README.md) for the bridge protocol.
