# HyperOS AAU (Auto Unlocker)

A simple, easy-to-use Android app that helps you auto-reserve a bootloader unlock slot for your Xiaomi or HyperOS device. Securing an unlock slot is notoriously difficult because spots run out seconds after midnight. This app does the heavy lifting for you by sending your request at exactly the right moment!

## ✨ Why use this app?

- **Smart Timing**: Instead of guessing when to press the button, the app measures how long it takes for your internet to talk to Xiaomi's servers and sends the request exactly when it needs to.
- **Perfect Clock Sync**: Your phone's clock might be a few seconds off. This app talks to global atomic clocks to make sure it runs at exact Beijing Midnight, down to the millisecond.
- **Safe & Accurate**: Instead of spamming the server and getting blocked, it sends just two perfectly timed requests at the exact split-second the spots open.
- **Runs on your Phone**: No need to leave a PC running all night. Just set it up on your phone and leave it open!

## 📋 What you need
- An Android device.
- A valid Xiaomi Community Account that meets the 30-day activity requirement for unlocking.
- Your secret "Cookie" (instructions below).

## 🔑 How to get your "Cookie"
To securely send the unlock request, the app needs your "Cookie"—a temporary code that proves you are logged into your Xiaomi account. 

1. Install a free network sniffer app on your phone (like **HTTP Toolkit**, **HTTP Sniffer** or **PCAPdroid**).
2. Turn on the sniffer so it starts recording your internet traffic.
3. Open the official **Xiaomi Community app** and go to the **Unlock Bootloader** page.
4. Go back to your sniffer app and search for a connection to:
   `https://sgp-api.buy.mi.com/bbs/api/global/apply/bl-auth`
5. Tap on that request and look for the **Headers** section. Find the line that says `Cookie:`. 
6. Copy the long jumble of text next to `Cookie:`.
7. Paste that text into the **Cookie String** box in this Auto Unlocker app!

## 🚀 How to Use
1. Download the app to your phone.
2. Paste your Cookie into the app.
3. Tap **"Verify & Start Process"**.
4. Leave the app open! It will turn on a countdown timer and automatically secure your unlock slot when Beijing midnight strikes. 

*Note: Make sure your screen stays on and your phone doesn't go to sleep while waiting, or the timer might get paused by Android!*

## 🛠 Developer Build Instructions
If you want to compile the project yourself:
1. Clone this repository locally.
2. Open the project in **Android Studio**.
3. Allow Gradle to automatically sync the Kotlin DSL scripts.
4. Ensure you have the Android SDK compiled for API 34.
5. Hit **Run** or use the terminal command `./gradlew assembleDebug`.
6. The generated APK will be found in `app/build/outputs/apk/debug/`.
