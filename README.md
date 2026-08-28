# RTSPS Kiosk Player

A single-purpose Android app that plays one RTSPS camera stream fullscreen,
forever, on a wall-mounted Amazon Fire tablet — and recovers by itself from
network loss, camera reboots, and silent stream stalls.

Built for Fire HD 8 / HD 10 (2021+), `arm64-v8a`, Fire OS 7/8. No Google Play
Services. Uses libVLC because the Android and Media3 RTSP stacks do not handle
`rtsps://` reliably.

## Install

1. Open the [Releases](../../releases) page in the tablet's browser.
2. Download the `.apk`.
3. Allow installs from the browser when prompted, then install.

No ADB required. Every push also builds an installable APK, available from the
Actions page.

## Using it

A thin bar sits at the bottom of the screen:

| Control | What it does |
|---|---|
| *name* | the stream currently playing |
| **Next** | switches to the next stream in list order, wrapping |
| **Setup** | add or delete streams, or tap one to switch to it |
| **Diag** | live state, health counters, and the event log |

On first launch, with nothing configured, the setup screen opens automatically.

### Adding a stream

Paste the URL (the **Paste** button reads the clipboard — typing these on a Fire
keyboard is miserable), optionally give it a name, and press **Add**. Only the
scheme and host are validated; you test a stream by tapping it and watching.

Unnamed streams show as `<no name>`. Order is the order you added them and never
changes, so a camera's position stays put.

### Diagnostics

While the Diag screen is open, the app serves its state on port 8080:

```
curl http://<tablet-ip>:8080/stats
```

The stream URL is masked in that response. The server runs **only** while the
screen is open — there is no listening socket during normal operation. While it
is open, the endpoint is unauthenticated and reachable by anything on the same
network; it exposes health counters, the event log, and the camera's host and
port, but never credentials.

## Fully Single App Kiosk

This app deliberately does not implement kiosk lockdown. Configure Fully to:

- **Start URL / app:** launch this app on boot
- **Kiosk Mode:** enabled — suppresses Home and Recents
- **Keep screen on:** enabled
- **Restart app on crash:** enabled

Do not give this app the `HOME` intent category; it would fight Fully for
launcher duties.

## Caveats

- **Fire OS auto-updates** reboot the tablet unannounced. Not a bug in this app.
- **Lockscreen ads** on ad-supported Fire models interrupt the display. You need
  the ad-free variant, or remove the component.
- **Battery swelling** is a real outcome for a tablet kept permanently on
  charge. Plan on replacing the device or the battery within a year or two.

## Licensing

This project's code is MIT. The distributed APK is effectively GPL-2.0-or-later
because of libVLC's bundled components — see [NOTICE.md](NOTICE.md).

## Development

CI-only: there is no local Android toolchain in this project's workflow. Push
and read the Actions log. All playback decision logic lives in
`app/src/main/java/io/github/srliao/kioskplayer/core/`, has no Android or VLC
imports, and is covered by JVM unit tests — that is what substitutes for a
debugger.
