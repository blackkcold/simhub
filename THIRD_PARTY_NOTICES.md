# Third-party notices

SIM Hub application source is MIT licensed.

The Android application uses Android platform APIs and is built with the Android Gradle Plugin. Android/OpenJDK trademarks and platform components remain subject to their respective licenses.

The server and PWA runtime code in this repository intentionally use standard-library/browser APIs and do not vendor JavaScript or Python runtime dependencies.


## QRCode.js (web/vendor/qrcode.min.js)

Copyright (c) 2012 davidshimjs. MIT License.
Source: https://github.com/davidshimjs/qrcodejs

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.


### Shizuku API and Provider (13.1.5)

- Components: `dev.rikka.shizuku:api:13.1.5`, `dev.rikka.shizuku:provider:13.1.5`
- Upstream: https://github.com/RikkaApps/Shizuku-API
- License: MIT (see upstream license: https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE)
- Use: optional explicit Shizuku Binder permission/status integration on Android. No privileged shell operations are run by SIM Hub.
