# Sharktooth feature guide

See the [README](../README.md) for installation and compatibility, or [Diagnostics](diagnostics.md) if a feature isn't working.

## Getting around

Sharktooth opens on **Dashboard**. Use the sidebar to switch pages, the gear icon to open **Settings**, and the bug icon to open **Debug**.

Under **Settings → Appearance**, choose **Classic** for a cabin-photo background or **Alternative** for a solid background. **Color mode** lets you choose Light, Dark or **Follow system**, which follows the head unit's day/night setting. **Sidebar position** places the sidebar on the left or right. Your choices are saved when you leave Settings.

## Customizable Dashboard

Card titles have a large glow matching the card background in Light and Dark modes to help them stand out over card graphics.
The Power Flow title has a stronger halo so the car illustration does not obscure its lettering.

Open **Dashboard** (gauge icon) for live vehicle and head-unit readings. The first page starts with nine cards; the other four start blank. Cards fill a three-column grid that scrolls vertically.

1. **Switch pages:** Swipe left or right, or tap one of the five dots above the cards. The largest dot marks the current page.
2. **Enter edit mode:** Hold a card or the Dashboard sidebar button for **1 second**. On a blank page, tap **Edit this dashboard**.
3. **Add or remove cards:** Tap **+** and choose a preview from the card library. Tap the red remove button on a card to return it to the library. You can use the same card on multiple pages, but only once per page.
4. **Rearrange cards:** Hold and drag a card. The blue marker shows where the card will be inserted. Pause over the position to preview the new order, or drag near the top or bottom edge to scroll.
5. **Finish editing:** Tap **Done** or press Back before switching pages. Each page saves its cards and order automatically, even if you leave it empty.

### What the cards show

| Card | Readings and controls |
| --- | --- |
| **Battery & Fuel** | Battery health, charge, fuel level, and electric, fuel and combined range estimates on one row, separated by vertical dividers. Beneath the title, **Trip Started** shows how many minutes ago you shifted into Drive and **Traveled** shows the distance since that shift. The green charge bar is `#00C45C` in light mode and `#33FF00` in dark mode; the blue bar shows fuel. The details popup shows the last detected fuel fill-up date and time, saved across restarts. A rise of at least 5 percentage points from the lowest observed fuel level counts, including a gradual rise; smaller fluctuations are ignored. The timestamp is when Sharktooth observed the rise, not a reconstructed fill time while the app was stopped. Before the first detected fill, it shows **Not recorded yet**. |
| **Drive** | Speed, gear, parking brake status and pedal use. The gauge sits behind the top readings in a compact card. The blue outer arc shows accelerator input and the yellow inner arc shows brake input. The centre shows the percentage above the active pedal name and a smaller grey **Pedal** caption, with brake taking priority. Dots and figures mark the highest readings recorded while Sharktooth was running that day, including on other pages. Peaks are saved across restarts and reset each day. |
| **Tyres** | Pressure and status for each tyre. The supplied wheel photo represents full inflation at 40 PSI. Each tyre independently selects a flat image below 10 PSI, then the 10, 20 or 30 PSI image for readings below the next 10-PSI boundary, and the original image at 40 PSI or above. The pictures are illustrative, not a physical tyre model; the exact PSI remains visible. Unknown readings do not display an inflation image. Green/orange/red dots preserve each tyre's status without tinting the photo. The card shows **OK** only when all four report normal status. |
| **Consumption** | Lifetime fuel economy, fuel used, electric consumption and energy charged. |
| **Mileage** | EV and HEV distances, odometer, drive time and average speed. |
| **Pitch / roll** | Separate gauges for front-to-back tilt and side-to-side tilt. The graphics stop at ±45°, but the numbers still show the measured angles. A dash means no reading. |
| **Head unit** | CPU, memory and storage usage. |
| **Engine** | Live RPM, oil level and a rolling 10-second RPM graph. Tap the card for live graphs covering the last 10 seconds, 5 minutes and 30 minutes. The trace uses the same amber as the engine path on Power Flow. History records through the background listener even on other pages or with Dashboard hidden; gaps mean no readings were available, and it starts fresh when the app process restarts. |
| **Chassis** | Outside temperature and steering angle. |

### Card details and history

Tap a card outside edit mode to open a large details popup. Battery & Fuel, Drive, Tyres, Consumption, Mileage, Pitch / roll, Head unit and Chassis show an enlarged, live version of the card beside current-day graphs of their numeric readings. The details/control column follows the configured sidebar side; the graph column is on the opposite side. Engine keeps its 10-second, 5-minute and 30-minute RPM graphs, with the enlarged card and Close button on the sidebar side. The fridge popup also places its controls and history-range selector on the sidebar side.

The Power Flow preview scales as a whole to fit beside its graph and above the regen controls, without wrapping or scrolling. It adjusts when the available popup space or displayed values change. Tap outside a details popup, or use its Close/Back control, to return to Dashboard.

Current-day dashboard history is sampled every 10 seconds through Sharktooth's background listener, including on other pages or with Dashboard hidden, saved locally across restarts, and starts a new history at local midnight. Returning to Dashboard shows the readings collected while away. Recording stops if the app/listener is stopped; it cannot reconstruct readings missed then. Unknown values and recording gaps remain gaps, not interpolated measurements. Storage errors are reported on screen. Static text such as vehicle identifiers is not graphed.

### Charge and fuel used on a trip

Each confirmed shift from **Park to Drive**, while the vehicle is ready, records the start time, odometer, charge and fuel levels immediately. The elapsed minutes and distance update while Dashboard is open. Missing trip or distance readings show a dash. Each bar keeps a translucent, colour-matched fill at its starting level behind the live fill; this starting-level fill is stronger in light mode. The grey, normal-weight figure beside the current percentage shows the net percentage points used, for example **74%** followed by **-2%** after leaving with 76%.

The starting levels stay fixed through stops, Dashboard navigation and app restarts, until the next Park-to-Drive shift. Sharktooth's background listener must be running to observe that shift; opening the app after departure cannot reconstruct missed starting levels. Unavailable departure readings are left unknown. Charging or refuelling reduces the net used figure, which is hidden when the current level is at or above the starting level. Sample data shows charge at **76% -6%** after leaving with 82%, and fuel at **61% -4%** after leaving with 65%.

### Optional cards

The Power Flow illustration keeps its proportions in a compact card, with readings layered over the image. The card scrolls with the Dashboard when it extends below the screen. Its orange engine path runs to the right of the blue/green battery path so simultaneous flows remain visible, both on the card and in its details popup.

- **Power Flow:** The green battery display shows current charge. **Regen today** appears at the top right; power and engine RPM appear at the bottom left. While the vehicle is moving, blue paths flow toward the wheels when the battery is supplying power, and green paths flow toward the battery when it is charging. An amber engine and path to the battery indicate the engine is running, not that the battery is necessarily charging. At 0 km/h the paths are hidden, but the power, charge and RPM readings remain visible. The animation shows overall flow, not which axle is active. **Regen today** estimates the energy recovered in kWh from readings collected by Sharktooth's background listener, including on other pages. It is saved across restarts and resets each day. Tap for a larger animated illustration, today's accumulated-regen graph and the **Standard / High** regen-mode selector. **High** maps to the bundled BYD API's Larger mode. Live changes require a readable mode, exported SDK constants and confirmed vehicle readback; unavailable or failed changes are displayed explicitly.
- **EVAKOOL fridge:** Shows both compartment temperatures, supply voltage, Eco/Max mode, power state and how recently the fridge reported in. Use **Reconnect** if the card shows **Fridge disconnected**. Tap the card outside edit mode to open controls and history.

The Drive speed, pedals and engine RPM are polled every 50 ms by the background listener, including with Dashboard hidden. The Power Flow power reading is polled every 20 ms. Actual updates depend on read time and the vehicle supplying new data; other readings keep their own refresh rates.

The debug app opens Dashboard with example readings and simulated daily graphs, not live vehicle data. Its regen selector is simulated and does not send vehicle commands or save preview history. The sample fridge card opens a simulated controls preview; changes there do not control a fridge.

## Camping Mode

Open **Modes** (tent icon) → **Camp Mode**. Use it only with the vehicle safely parked. It does not keep the vehicle powered, maintain cabin climate or monitor occupants. Headlights remain under your manual control.

In the debug app, Camp Mode opens a labelled preview showing **P** and **76%** battery. **Start Camping Mode** runs a simulated checklist without changing vehicle settings, Bluetooth, media volume or the screen. Touch the screen after completion to simulate restoration and see the headlight reminder. Preview cutoff changes and session progress are not saved; Save SoC is unavailable in the preview.

### Start a session

1. Put the vehicle in **P** and leave it switched **on**. The start button is disabled until Sharktooth confirms Park and can read a battery charge above the cutoff. Turn the headlights off manually; the note below the start button highlights this reminder.
2. Set **Battery cutoff** with **−** and **+** before starting. The default is **10%**. The setting is saved and cannot be changed during a session.
3. Keep hands and objects clear of all windows, then tap **Enable Camping Mode**. The process starts without a confirmation popup. Use **STOP WINDOWS** if you need to halt window movement while the steps are running.

The read-only **Actions** checklist runs down the left column first, then down the right column. Each completed step shows a white tick inside a green circle. Sharktooth turns off connected DiKey strip and button lights, turns off head-unit Bluetooth, closes the windows, dims the instrument cluster and mutes media. It then disconnects active CarPlay, turns off climate and daytime running lights (DRLs), turns off the head-up display (HUD) and finally turns off the centre screen. A failed step turns red and shows its error below the left card. Entry stops immediately and Sharktooth automatically restores the steps already attempted; it does not run the remaining entry steps. The DiKey lighting step is skipped if no bar is connected; it does not switch off the dial displays. Progress and errors are kept across app restarts.

### End a session

1. Touch the centre screen to wake it and start restoration. Leaving **P** also ends Camping Mode automatically. If the vehicle is switched off during a session, restoration starts when it next confirms **READY**. A saved session recovered after the app restarts or the head unit resumes from suspension is also restored once READY is confirmed, rather than silently continuing Camping Mode. These safeguards work while Sharktooth is running, even if the Camp Mode page is closed. Reaching the battery cutoff also starts restoration; a missing battery reading does not trigger it, and the cutoff does **not** switch the vehicle off.
2. Stay in **P** while Sharktooth restores the cluster brightness, HUD, media and DRLs and re-enables head-unit Bluetooth and connected DiKey lighting. Cluster brightness returns to its saved level; if no level was saved, it uses level 14 of 22 (approximately 60%). Each resolved step unticks. A failed recovery stays red with its error below the left card, but the remaining recovery actions continue. Camp Mode then turns off even if recovery failed, and the start button returns.
3. When the **Headlight reminder** appears, turn the headlights back on and visually check that lighting is normal before driving. If anything failed, the popup also lists the recovery actions to perform manually. If Sharktooth cannot save the exit, it warns that the saved session might return after an app restart. The windows remain closed, climate stays off, and CarPlay does not restart automatically. Turn climate back on and reconnect your phone normally if needed.

### Save SoC

**Save SoC** sets the vehicle's battery target separately from the Camp Mode cutoff. Turn on the switch, then move and release the slider to apply the target. You can choose **25–100%** up to the maximum supported by the vehicle. If the vehicle does not confirm the change, Sharktooth tells you. Turning the switch off disables the slider but leaves the last reported target visible. Starting or ending Camping Mode does not change Save SoC.

## Pet Mode

Open **Modes → Pet Mode** while the vehicle is **READY**. Set the temperature, fan speed, recirculation and airflow direction. While active, Pet Mode keeps the centre screen awake and reapplies your climate settings as needed. When you exit Pet Mode, including by switching to another sidebar page, Sharktooth restores the previous climate and display brightness.

## ADAS profiles

Open **ADAS** to view the current driver-assistance settings or save and apply a profile for ELKA, LDA, AEB, DMS and **Forward collision warning (FCW)**. FCW has **Late**, **Moderate**, **Early** and **Off** options. Live changes are confirmed by vehicle readback; unreadable FCW disables its live controls instead of guessing. In Custom mode, FCW is left unchanged until you explicitly select an option, so existing profiles do not change FCW automatically. Once selected, it is saved and included in Apply and automatic application. Hold the ADAS sidebar button to configure automatic application on startup.

On the BYD AUTO / Android 11 head unit, parked ADB tests and independent checks of the original BYD Settings screen confirmed `setPredictCrashAlertMode`: Late writes **2**, Moderate **3**, Early **4**, and Off **1**. `getPredictCrashAlert138Mode` and `getPredictCrashAlert32DMode` read Late **1**, Moderate **2**, Early **3**, and Off **0**. The original Late setting was restored and visually confirmed. OEM Settings prefers the 138 getter when `Bodywork.hasMessage(112)` reports present; otherwise it uses 32D. The SDK's `PREDICT_CRASH_ALERT_*` constants do not match these write values and must not be used to infer them.

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

To update, open **Settings → Updates → Check for updates**. If an update is available, choose **Download & install** and follow Android's installer prompts. Public builds check only stable releases. Head-unit dev builds (installed version ending in `-dev`) check only dev prereleases; sideload the signed dev APK once to opt in. Both channels check manually and retain real vehicle controls. You can also download the APK from [GitHub Releases](https://github.com/DJS91/Sharktooth/releases). See [dev builds and public releases](../README.md#dev-builds-and-public-releases) for signing and switching back to stable.

Under **Settings → Sentry**, use **Download & install** to install Strike for 360° recording. **Details** provides app and update information; **Open Strike** appears when it is installed.

## Debug tools

Use **Debug** for troubleshooting, not for normal Camping Mode operation. **Camp Mode interactive tests** lets you capture a baseline, change one setting using the vehicle controls and select **Capture and compare**. Open **Show results** for the latest summary or **Raw details** for the saved report. **Clear test session** removes the report without undoing vehicle settings.

The test page includes controls for checking climate, screen dimming and wake, windows, daytime running lights and headlights without starting Camp Mode. It also lets you observe headlight changes and record the screen-off shortcut. Only run vehicle-control tests while safely parked. Keep clear of the windows, use **STOP WINDOWS** if needed, and manually restore and verify normal lighting before driving if you run a lighting test. See [Diagnostics](diagnostics.md) for more troubleshooting options.
