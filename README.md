# HyperOS AAU (Auto Unlocker)

A native Android application designed to automate the Xiaomi/HyperOS Bootloader Unlock slot reservation process by sending precision-timed requests at exactly Beijing midnight.

## 🚀 Features

- **JIT Latency Detection**: Measures server ping exactly 10 seconds before midnight for pinpoint accuracy.
- **NTP Time Synchronization**: Utilizes `pool.ntp.org` to bypass your phone's clock drift and calculate true Beijing standard time down to the millisecond.
- **Dual-Burst Bracketing Strategy**: Calculates mathematical packet arrival times and dual-fires packets at exactly [-50ms, +50ms] of 00:00:00.000 for maximum success rate while passing anti-spam matrices.
- **Native Android Compose UI**: Clean, standalone mobile interface.

## 📋 Requirements
- An Android device running Android 8.0+
- A valid, active Xiaomi Community Account that meets the 30-day activity requirements for unlocking.
- The captured Session Cookie (instructions below).

## 🔑 How to get your BBS Cookie
To authorize the unlock requests, this application needs your raw session cookie string from the official Xiaomi Community app. 

1. Install a network sniffer on your phone or PC (e.g., **HTTP Toolkit**, **PCAPdroid**, or **Charles Proxy**).
2. Start intercepting traffic.
3. Open the official Xiaomi Community app and navigate to the **Unlock Bootloader** apply screen.
4. Go back to your sniffer and search for a request made to:
   `https://sgp-api.buy.mi.com/bbs/api/global/apply/bl-auth`
5. Look at the **Headers** of that request. Copy the entire string next to the `Cookie:` header (it usually looks like long alphanumeric strings separated by semicolons).
6. Paste that string straight into the **Cookie String** box in this Android application.

## ⚙️ Build and Run
Clone this project and open it in **Android Studio**. Build the project and deploy the `.apk` directly to your Android device. 

*Note: Ensure your device does not put the application to sleep during the countdown, or timing precision will fail.*
