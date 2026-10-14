# Distribution preparation

The delivered build is a GitHub-side experimental APK with a separate package
ID. It does not publish to Google Play, replace a signed stable APK, or modify
main. Stable GitHub Release self-update is disabled in the preview build.

A separate `storePreview` build uses `com.blackkcold.simhub.storetest`, disables external updates/OTA and the companion SMS permission path, and removes installation/Shizuku permissions/providers. It is a local validation channel, not a published or approved store artifact.

Application-store rollout remains a separate approval gate: a default SMS
handler must supply the full user-facing SMS experience and receive SMS/call-log
permission approval; non-default remote-control access must not be represented
as an approved exception. Store builds must disable external APK installation,
GitHub self-update and compatibility/ADB experiments, and use store updates.
Plan Play App Signing/upgrade certificates before a public store release.

Foreground specialUse purpose and local network permissions need store review
and platform-specific testing. A new transport cannot bypass Android protected
SMS/OTP or background restrictions. An eventual public connection service needs
operational abuse controls, privacy disclosures, retention rules, cost limits
and independently validated TLS/TURN deployment. Source code and build tests
alone do not satisfy those requirements.
