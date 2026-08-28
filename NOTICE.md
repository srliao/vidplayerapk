# Notices

## This project's own code

MIT — see `LICENSE`. Every source file in this repository is MIT-licensed.

## The distributed APK

The APK is **not** MIT-only. It embeds libVLC via
`org.videolan.android:libvlc-all:3.7.5`, whose `libvlc.so` statically links:

- **libVLC core** — LGPL-2.1-or-later
- **libdvdnav, libdvdread** — **GPL-2.0-or-later**
- FFmpeg, live555, GnuTLS and others under their respective licenses

Because GPL-2.0-or-later components are present, **the APK as a whole is
effectively GPL-2.0-or-later**, even though this repository's own source is MIT.
That obligation is satisfied here by construction: this repository is public and
contains the complete corresponding source for the application, and libVLC's
source is published by VideoLAN.

- libVLC version: 3.7.5
- libVLC source: https://code.videolan.org/videolan/libvlcjni
- Rebuilding against a different libVLC: change the `libvlc` version in
  `gradle/libs.versions.toml` and rebuild.

A pure-LGPL binary would require a custom libVLC build configured with
`--disable-dvdnav --disable-dvdread`. This project does not do that.

This is a description of the licensing situation, not legal advice.
