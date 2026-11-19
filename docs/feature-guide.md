# Sharktooth feature guide

See the [README](../README.md) for installation and compatibility, or [Diagnostics](diagnostics.md) if a feature isn't working.

## Getting around

Sharktooth opens on **Dashboard**. Use the sidebar to switch pages, the gear icon to open **Settings**, and the bug icon to open **Debug**.

Under **Settings → Appearance**, choose **Classic** for a cabin-photo background or **Alternative** for a solid background. **Color mode** lets you choose Light, Dark or **Follow system**, which follows the head unit's day/night setting. **Sidebar position** places the sidebar on the left or right. Your choices are saved when you leave Settings.

## Customizable Dashboard

Open **Dashboard** (gauge icon) for live vehicle and head-unit readings. The first page starts with nine cards; the other four start blank. Cards fill a three-column grid that scrolls vertically.

1. **Switch pages:** Swipe left or right, or tap one of the five dots above the cards. The largest dot marks the current page.
2. **Enter edit mode:** Hold a card or the Dashboard sidebar button for **1 second**. On a blank page, tap **Edit this dashboard**.
3. **Add or remove cards:** Tap **+** and choose a preview from the card library. Tap the red remove button on a card to return it to the library. You can use the same card on multiple pages, but only once per page.
4. **Rearrange cards:** Hold and drag a card. The blue marker shows where the card will be inserted. Pause over the position to preview the new order, or drag near the top or bottom edge to scroll.
5. **Finish editing:** Tap **Done** or press Back before switching pages. Each page saves its cards and order automatically, even if you leave it empty.

### What the cards show

| Card | Readings and controls |
| --- | --- |
| **Battery & Fuel** | Battery health, capacity, charge, fuel level, and electric, fuel and combined range estimates. The bright green (`#33FF00`) bar shows charge; the blue bar shows fuel. |
| **Drive** | Speed, gear, parking brake status and pedal use. The blue outer arc shows accelerator input and the yellow inner arc shows brake input. The centre shows the percentage above the active pedal name and a smaller grey **Pedal** caption, with brake taking priority. Dots and figures mark the highest readings recorded while Dashboard was open that day. Peaks are saved across restarts and reset each day. |
| **Tyres** | Pressure and status for each tyre. The card shows **OK** only when all four report normal status. |
| **Consumption** | Lifetime fuel economy, fuel used, electric consumption and energy charged. |
| **Mileage** | EV and HEV distances, odometer, drive time and average speed. |
| **Pitch / roll** | Separate gauges for front-to-back tilt and side-to-side tilt. The graphics stop at ±45°, but the numbers still show the measured angles. A dash means no reading. |
| **Head unit** | CPU, memory and storage usage. |
| **Engine** | Live RPM, power, oil level and a rolling one-hour RPM graph. The trace uses the same amber as the engine path on Power Flow. It records while Dashboard is open; gaps mean no readings were collected. The graph starts fresh when the app restarts. |
| **Chassis** | Outside temperature and steering angle. |

### Optional cards

Dashboard card titles have a soft grey glow. The Power Flow illustration keeps its proportions in a compact card, with readings layered over the image. The card scrolls with the Dashboard when it extends below the screen.

- **Power Flow:** The green battery display shows current charge. The power reading appears at the top right, with engine RPM below it; **Regen today** appears at the bottom left. While the vehicle is moving, blue paths flow toward the wheels when the battery is supplying power, and green paths flow toward the battery when it is charging. An amber path shows that the engine is running. At 0 km/h the paths are hidden, but the power, charge and RPM readings remain visible. The animation shows overall flow, not which axle is active, and engine RPM alone does not mean the battery is charging. **Regen today** estimates the energy recovered in kWh from readings collected while Dashboard is open. It is saved across restarts and resets each day.
- **EVAKOOL fridge:** Shows both compartment temperatures, supply voltage, Eco/Max mode, power state and how recently the fridge reported in. Use **Reconnect** if the card shows **Fridge disconnected**. Tap the connected card outside edit mode to open controls and history.

If the Dashboard is labelled **Sample data**, the readings are examples, not live vehicle data. The sample fridge card is display-only.

## Camping Mode

Open **Modes** (tent icon) → **Camp Mode**. Use it only with the vehicle safely parked. It does not keep the vehicle powered, maintain cabin climate or monitor occupants. Headlights remain under your manual control.

### Start a session

1. Put the vehicle in **P** and leave it switched **on**. The start button is disabled until Sharktooth confirms Park and can read a battery charge above the cutoff.
2. Set **Battery cutoff** with **−** and **+** before starting. The default is **10%**. The setting is saved and cannot be changed during a session.
3. Keep hands and objects clear of all windows, then tap **Enable Camping Mode**. The process starts without a confirmation popup. Use **STOP WINDOWS** if you need to halt window movement while the steps are running.

The read-only **Actions** checklist marks each completed step. Sharktooth turns off connected DiKey strip and button lights, disconnects active CarPlay, turns off head-unit Bluetooth and climate, closes the windows, turns off daytime running lights (DRLs), dims the instrument cluster, turns off the head-up display (HUD), mutes media and turns off the centre screen. A failed step stops entry. The DiKey lighting step is skipped if no bar is connected; it does not switch off the dial displays. Checklist progress is kept across app restarts and clears after successful restoration.

### End a session

1. Touch the centre screen to wake it and start restoration. Reaching the battery cutoff also starts restoration automatically while Sharktooth is running, even if the Camp Mode page is closed. A missing battery reading does not trigger restoration, and the cutoff does **not** switch the vehicle off.
2. Stay in **P** while Sharktooth restores the cluster brightness, HUD, media and DRLs and re-enables head-unit Bluetooth and connected DiKey lighting. If restoration is incomplete, open **Camp Mode** and tap **Retry restoring settings**.
3. When the **Headlight reminder** appears, turn the headlights back on and visually check that lighting is normal before driving. The windows remain closed, climate stays off, and CarPlay does not restart automatically. Turn climate back on and reconnect your phone normally if needed.

### Save SoC

**Save SoC** sets the vehicle's battery target separately from the Camp Mode cutoff. Turn on the switch, then move and release the slider to apply the target. You can choose **25–100%** up to the maximum supported by the vehicle. If the vehicle does not confirm the change, Sharktooth tells you. Turning the switch off disables the slider but leaves the last reported target visible. Starting or ending Camping Mode does not change Save SoC.

## Pet Mode

Open **Modes → Pet Mode** while the vehicle is **READY**. Set the temperature, fan speed, recirculation and airflow direction. While active, Pet Mode keeps the centre screen awake and reapplies your climate settings as needed. When you exit Pet Mode, including by switching to another sidebar page, Sharktooth restores the previous climate and display brightness.

## ADAS profiles

Open **ADAS** to view the current driver-assistance settings or save and apply a profile for ELKA, LDA, AEB and DMS. Hold the ADAS sidebar button to configure automatic application on startup.

## CarPlay and Android Auto

When a phone is projecting, tap its shortcut at the bottom of the sidebar. To switch automatically, enable **Switch to CarPlay automatically** in **Settings → Startup**. It works for CarPlay and Android Auto while Sharktooth's home screen is open. The app waits for the phone connection and for your ADAS profile if auto-apply is enabled, then shows a three-second countdown. Tap anywhere to cancel.

## DiKey Controls Bar

When the button bar is connected, open **Controls Bar** (two-dial icon) in the sidebar. Use **Colours** for lighting and **Buttons** for button assignments.

### Lighting

1. In **Colours**, choose the **Day** or **Night** profile. The page opens on the profile currently active on the bar. Selecting the other profile previews it on the bar. The vehicle's ambient light switches profiles automatically when you leave this page.
2. Tap a zone to expand it: left, middle or right ambient bar, or the button backlight. Use the colour bar, saturation, brightness and RGB sliders to adjust the zone. Changes are saved to the selected profile, and the preview shows the colours and brightness as you adjust them.
3. With colour sync off, choose **Off**, **Solid**, **Breath**, **Blink** or **Flow** for each ambient bar. The on-screen preview shows colour and brightness, not the lighting animation. It also shows the latest dial values when available.
4. Use **Sync bar colours** to give all three ambient bars the same colour without changing the button backlight or the bars' individual modes. The first sync uses the left bar's colour. Turn sync off to edit the bars separately again; turning it back on recalls the last shared colour for that profile.

### Buttons and dials

Use **Buttons** to choose what happens when you press down, hold down, press up or hold up. The downward press runs the printed climate action by default. You can also map gestures to launch an app or run a custom Android action.

The left dial adjusts the passenger side and the right dial adjusts the driver side. Rotate to change temperature. Press a dial to switch between temperature and fan speed, then rotate to adjust. The dial returns to temperature after three seconds without a turn in fan mode.

| Button | Default downward press |
| --- | --- |
| 1 | Climate on/off |
| 2 | A/C compressor |
| 3 | Cycle airflow direction |
| 4 | Recirculation |
| 5 | Auto |
| 6 | Front defog |
| 7 | Rear window / mirrors |
| 8 | Air only |
| 9 | Sync driver and passenger temperatures |
| 10 | Max cool |

## EVAKOOL camping fridge

Add the **EVAKOOL** card to Dashboard, then tap it to open the fridge controls. Use the power switch, the target-temperature buttons for each compartment, and the **Eco / Max** selector. The history chart shows temperatures and targets for the last hour, day or week. Notifications can alert you if the fridge stops reporting or a compartment warms above its target.

The fridge normally connects through the ESP32 USB bridge. In the fridge page's **More** menu, you can choose direct head-unit Bluetooth instead, select the fridge, check the connection, or clear history. See the [fridge guide](camping-fridge.md) for compatible models, connection setup and alert conditions.

## Wi-Fi and Home Assistant

- **ESP32 Wi-Fi:** In **Settings → Wi-Fi**, connect the bridge to a 2.4 GHz network. See [Wi-Fi setup](esp32-wifi.md).
- **Home Assistant:** In **Settings → Telemetry**, opt in to sharing vehicle, climate, ADAS and location readings. This feature needs the USB bridge connected to Wi-Fi. See [Home Assistant setup and privacy](home-assistant-telemetry.md).

## Background connection and updates

Open Sharktooth once after powering on the head unit. The background listener keeps the DiKey connection active while the app is in the background. See the [autostart guide](../autostart.md) for head-unit settings and limitations.

To update, open **Settings → Updates → Check for updates**. If an update is available, choose **Download & install** and follow Android's installer prompts. You can also download the APK from [GitHub Releases](https://github.com/DJS91/Sharktooth/releases).

## Debug tools

Use **Debug** for troubleshooting, not for normal Camping Mode operation. **Camp Mode interactive tests** lets you capture a baseline, change one setting using the vehicle controls and select **Capture and compare**. Open **Show results** for the latest summary or **Raw details** for the saved report. **Clear test session** removes the report without undoing vehicle settings.

The test page includes controls for checking climate, screen dimming and wake, windows, daytime running lights and headlights without starting Camp Mode. It also lets you observe headlight changes and record the screen-off shortcut. Only run vehicle-control tests while safely parked. Keep clear of the windows, use **STOP WINDOWS** if needed, and manually restore and verify normal lighting before driving if you run a lighting test. See [Diagnostics](diagnostics.md) for more troubleshooting options.
