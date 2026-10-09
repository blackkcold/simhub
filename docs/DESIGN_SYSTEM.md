# SIM Hub · Visual design system

## Source of truth

SIM Hub uses one visual identity across the Web/PWA and the native Android agent.

| Surface | Asset / token source |
|---|---|
| Web & PWA icon | `web/icon.svg` |
| PWA manifest / install icon | `web/manifest.webmanifest` |
| Android launcher & in-app mark | `android/app/src/main/res/drawable/ic_simhub.xml` |
| Android light & dark tokens | `android/app/src/main/res/values*/colors.xml` |
| Web semantic tokens & responsive layout | `web/styles.css` |
| Brand banner | `docs/assets/brand-banner.svg` |
| Interface previews | `docs/assets/ui-{desktop,devices,mobile,android}.svg` |

### Color tokens

| Role | Light | Dark |
|---|---|---|
| Canvas | `#F5F7FC` | `#0C1223` |
| Surface | `#FFFFFF` | `#151E32` |
| Primary action | `#574FEA` | `#A49DFF` |
| Main text | `#18223A` | `#F0F4FF` |
| Supporting text | `#64718A` | `#ADB9D0` |
| Border | `#E2E7F1` | `#303C57` |

Keep contrast, tap size, keyboard focus visibility and reduced-motion preferences intact. Translate UI copy through `web/i18n.js` and Android `strings.xml`; do not embed UI labels in production CSS. Do not change security actions or authentication semantics as part of cosmetic revisions.

### Screenshot and preview policy

The SVGs in this repository are **illustrative UI layouts**, not claims of live production screenshots. They contain synthetic/redacted text and explicitly say `DEMO / 示例界面`. The previews illustrate the actual navigation and current feature surfaces: desktop conversations, device/SIM details, encrypted diagnostic results, mobile direct replies, and native Android node controls. They remain **illustrations with synthetic data**, not recorded production screens or proof of carrier/hardware tests. The mobile preview depicts the PWA; the Android preview depicts the native Agent.

If replacing them with real screenshots, capture on test devices with mock messages and a test-only server. Redact or eliminate recipient numbers, OTP codes, device identifiers, hostnames, admin tokens, recovery material and TOTP values. Validate the visual output at mobile, tablet and desktop widths, in light/dark themes and Chinese/English. Never publish real messages or keys in documentation.

### 品牌维护

- Web 与 Android 必须同时更新图标及配色。
- README 图仅使用虚构或脱敏数据，不展示真实验证码、手机号与设备凭据。
- 文案由现有国际化资源维护，预览图不是功能测试证明。

### Responsive workspace rules

- The mobile inbox fills the viewport area left after the header, filters and floating bottom navigation; no fixed half-screen max-height.
- Device cards are short summaries; SIM/channel metadata, sync actions and destructive controls live in the detail dialog.
- Device enrollment has two modes: QR/copy one-time package, or node URL plus Android eight-digit pairing code and fingerprint verification.
- Settings use category navigation with a focused single-column content area. All new UI labels are localized in `web/i18n.js`.
- Mobile dialogs use safe-area-aware viewport sizing; reduced-motion users are respected.

### Current UI inventory

| Surface | Production behavior | Source |
|---|---|---|
| Admin login | Username, Admin Token/TOTP, optional Passkey authentication; Vault passphrase remains local | `web/index.html`, `web/app.js` |
| Inbox | SMS threads, reply in selected conversation, right-aligned new-message action, locally decrypted search | `web/app.js` |
| Devices | Compact responsive summary cards, SIM/channel detail dialog, two-path QR/link or eight-digit-code setup wizard, sync diagnostics and safety controls | `web/app.js`, `web/styles.css` |
| Networking | OS-default data SIM fallback policy validation; system mobile settings shortcut (Android) | `android/app/src/main/java/com/blackkcold/simhub/NetworkFailoverPolicy.java` |
| Settings | Categorized account/security, notification and system/update views; no node enrollment controls | `web/index.html`, `android/app/src/main/res/layout/activity_main.xml` |

**Documentation screenshots must not invent support for privileged Android data-SIM switching, background push-delivered OTP content, full MMS, or call handling.** UI messages and responsive behavior should be tested at desktop/phone/foldable widths. The repository runs Playwright screenshots for multiple browser sizes and light/dark themes in CI; these are regression artifacts, not proof of a fully provisioned physical SIM.
