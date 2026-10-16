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

Foreground specialUse purpose and local network permissions need store review and platform-specific testing. A new transport cannot bypass Android protected SMS/OTP or background restrictions. The preview no longer uses any public signaling service: it reuses the already registered management center for encrypted SDP/ICE exchange and offers only public STUN (no default TURN, no content relay). Center availability is the operator's own infrastructure; the bundled STUN hosts are third-party and outside SIM Hub control. Source tests do not establish real-SIM/OEM reliability or store acceptance.

A private packaging build carries the real center origin and its two SPKI pins, injected only through a protected GitHub Environment. That APK necessarily contains the host name, which is an accepted exception to the "no domain in public source or logs" rule; the host is still not an access-control mechanism. Public CI and preview builds carry only a fake description derived from the checked-in test certificate.
