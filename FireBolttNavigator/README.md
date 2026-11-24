# FireBoltt Navigator (Android / Java)

A first Android Studio prototype for the Nothing Phone (2a) + Fire-Boltt 046 (MOYOUNG-V2).

## What is implemented

- Destination search from the phone using Nominatim (manual search button; no keystroke autocomplete).
- Driving route calculation from current phone GPS using OSRM.
- OSM street map in the phone app, with route line, start marker, destination marker, and live phone location marker.
- Next-maneuver text and approximate distance to the next maneuver.
- BLE scan/connect to the Fire-Boltt 046 using its known address `F1:70:C6:5B:E3:F6`.
- GATT discovery for the captured MoYoung V2 service and characteristics: FEEA / FEE2 / FEE3 / FEE6.
- A watch route-card renderer at 368 x 448, encoded as a MoYoung Type-C face with RGB565 + RLE_LINE blobs and a 140 x 163 gallery preview.
- Experimental Type-C face upload using the 0x74 request/244-byte chunk protocol.
- Optional turn text alerts using MoYoung command 0x41 when the watch is connected.

## Important watch limitation

The BLE **0x6E** wallpaper transfer was verified earlier against the user's Fire-Boltt 046 using Da Fit-converted payloads. The proprietary Da Fit image encoder for 0x6E has not been reverse-engineered, so this app does not pretend raw RGB565 is a valid 0x6E wallpaper.

This prototype instead builds a standards-documented MoYoung Type-C face and attempts the separate **0x74** face-upload flow. Type-C face structure and the generic 0x74 upload path are documented by community MoYoung tools, but this combination has **not yet been verified on this exact Fire-Boltt 046 / MOY-KLA3-2.0.5 firmware**. The route upload is therefore experimental. The app shows a warning and requires the user to press **SEND ROUTE CARD TO WATCH** explicitly. It does not start a watch upload automatically.

The watch card is a route overview (route line, start/end/current markers, next maneuver); it is not a full street-tile map. The phone displays the full OSM street map. A static route card is uploaded at the user's request; subsequent turn changes may be sent as short text alerts. The route card does not automatically refresh on every GPS fix.

## Build in Android Studio

1. Install Android Studio and an Android SDK Platform for API 35.
2. Extract/open this `FireBolttNavigator` folder in Android Studio (`File > Open`).
3. Let Android Studio sync Gradle. The project uses Android Gradle Plugin 8.9.2 and Java 17.
4. Connect the Nothing Phone (2a) with USB debugging enabled, or run on a device using Android 12 or newer.
5. Press **Run** to install the app.

This source package was created in an environment without the Android SDK/Gradle installation, so no APK was built or device-tested here. Android Studio must perform the first build.

## First use

1. Enable Location and Bluetooth on the phone.
2. Open FireBoltt Navigator and grant **Precise location** and **Nearby devices** permissions.
3. Search a destination and choose the correct result.
4. Tap **CALCULATE ROUTE**. Wait for a GPS fix; the phone map should display the route.
5. Close/force-stop Da Fit before connecting to the watch. The watch normally permits only one active GATT connection.
6. Tap **CONNECT WATCH**. The app checks for an existing GATT connection, then connects to the known watch address and discovers FEE2/FEE3/FEE6.
7. Tap **SEND ROUTE CARD TO WATCH**. The app creates a Type-C face and attempts the experimental 0x74 transfer. Keep the watch close to the phone and keep this app open.
8. If the upload reports completion, check the watch face selector for the newly uploaded face. If it does not appear, switch back to your previous face. Do not repeatedly retry failed uploads without reviewing the error.

## Runtime behavior / limitations

- Keep the app visible while navigating. This initial build tracks GPS while its Activity is in the foreground and intentionally stops location updates when the app is backgrounded. A foreground navigation service is a later enhancement.
- The app keeps the phone screen awake while it is open. Tap **STOP NAVIGATION** when finished.
- The public OSRM routing demo is best-effort and has no service-level guarantee. It is suitable for a prototype, not as the sole source of safety-critical navigation.
- Nominatim is queried only after the user taps Search. Do not add autocomplete or rapid repeat queries: the public service policy sets an absolute maximum of one request per second and requires an identifying User-Agent.
- OpenStreetMap standard tiles are fetched only for the visible map and the map attribution is shown in the UI. The app sets an identifying User-Agent in its WebView.
- Internet access is required for geocoding, routing and map tiles. This prototype does not provide offline maps or rerouting when the user leaves the route.
- Only driving mode is exposed in this first build because the public OSRM demo deployment may not have walking/cycling profiles enabled.

## Protocol implementation notes

- BLE service: `0000feea-0000-1000-8000-00805f9b34fb`
- FEE2: control writes
- FEE3: watch notifications / requested chunk indexes / completion
- FEE6: raw face-file chunks
- Negotiated MTU must be 247 for 244-byte chunks; the app refuses the Type-C transfer if MTU 247 was not negotiated.
- File type uses the Type-C header size (1900 bytes), file ID `0x81`, full-screen background field `0x01`, optional preview blob (140 x 163), RGB565 big-endian pixels and Type-C RLE_LINE compression when it fits.
- 0x74 upload sequence: announce file length on FEE2, answer watch-requested chunk indexes through FEE6, send success confirmation on completion, then request display-list index 13. This last step is model-dependent and must be tested on the target firmware.

## Main source files

- `MainActivity.java` — UI, destination search, route lifecycle, GPS updates, watch integration.
- `RouteEngine.java` — Nominatim geocoding and OSRM route parsing.
- `WatchFaceEncoder.java` — renders and packages the 368 x 448 route card as a Type-C face.
- `WatchBleClient.java` — BLE scanning, connection checks, GATT setup and experimental 0x74 upload.
- `app/src/main/assets/map.html` — Leaflet / OpenStreetMap phone map.


## Live watch navigation mirror

The Android app now has an opt-in live watch mirror. Calculate a route, connect the Fire-Boltt, then tap **START LIVE NAVIGATION ON WATCH** once to install the route view and enable refreshes.

- GPS updates continue to drive the phone map and route-step progress.
- The watch face is rebuilt around the current GPS position and shows only the next 100 m of route (plus 20 m behind the current fix for context). The surrounding local map window is approximately 180 m wide; the rest of the journey is hidden.
- The watch image is refreshed after roughly 35 m of movement or a next-turn change, with a minimum 45-second interval between full face uploads.
- Turn text alerts are sent separately when the maneuver changes, and distance buckets update no more often than every 12 seconds.
- Only one full face upload is allowed at a time. A refresh requested during an upload is queued and reconsidered after completion.
- Stop Navigation disables the live mirror.

**Important limitations:** the full-image refresh still uses the experimental MoYoung Type-C / 0x74 upload flow. That flow has not been verified on the exact Fire-Boltt 046 firmware, so successful BLE transfer does not guarantee that the watch firmware will replace/render every refreshed face. The app must remain open and receiving GPS updates; this branch does not yet provide background/foreground-service navigation or automatic off-route recalculation. Keep Da Fit disconnected while testing the BLE link.
