# Third-Party Notices

## HEV Socks5 Tunnel

- Source: <https://github.com/heiher/hev-socks5-tunnel>
- Pinned revision: `9a06bc6e7989da54e3d32ff701ef7a7ce4995d3a`
- License: MIT License, Copyright (c) 2022 hev

The Android JNI libraries under `app/src/main/jniLibs` are built from that
revision with a project-specific JNI class binding. Rebuild them with
`scripts/build-hev-tun.ps1` when changing the pinned source or Android NDK.

## XTLS libXray

- Source: <https://github.com/XTLS/libXray>
- Tested revision: `80263da83e96b2972455b0a94b13ee1a10e51391`
- License: MIT License, Copyright (c) 2023-2025 XTLS

The Android `libXray.aar` is supplied as a locally ignored release input. The
Gradle release build verifies its SHA-256 against `app/libs/libXray.properties`;
rebuild or replace it only through the documented pinned-revision process.
