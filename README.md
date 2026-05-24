# SMS Relay — Android App

Automatically forwards every incoming SMS to **Email (Gmail/SMTP)**, **Google Chat**, **WhatsApp**, or **Google Voice** — configurable from a single settings screen.

---

## Quick Start

### 1. Open in Android Studio
- File → Open → select the `SmsForwarder` folder
- Let Gradle sync (it downloads JavaMail + Material dependencies)

### 2. Build & install
```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```
Or use the ▶ Run button in Android Studio.

---

## Forwarding Methods

### ✉️ Email (SMTP) — fully automatic, no user interaction needed
| Setting | Example |
|---|---|
| Recipient email | you@gmail.com |
| SMTP Host | smtp.gmail.com |
| Port | **587** (STARTTLS) or **465** (SSL) |
| Username | your-sender@gmail.com |
| Password | **App Password** (not your regular Gmail password) |

**How to get a Gmail App Password:**
1. [myaccount.google.com](https://myaccount.google.com) → Security
2. Enable 2-Step Verification
3. Search "App Passwords" → create one for "Mail / Android device"
4. Paste the 16-character password into the app

Works with any SMTP provider (Outlook, Yahoo, custom SMTP).

---

### 💬 WhatsApp — opens WhatsApp with pre-filled message
- Enter the destination phone number in **international format** (e.g. `+14155552671`)
- Requires WhatsApp to be installed on the device
- **Note:** opens the WhatsApp app, so the screen needs to be on

---

### 📞 Google Voice — SMS relay (silent, automatic)
- Enter your **Google Voice number** (e.g. `+12025551234`)
- The app sends an SMS to that number; GV logs it and notifies you
- Uses your carrier's SMS — standard rates may apply
- Requires `SEND_SMS` permission

---

## Permissions required

| Permission | Why |
|---|---|
| `RECEIVE_SMS` | Detect incoming SMS |
| `READ_SMS` | Read message content |
| `SEND_SMS` | Google Voice relay only |
| `INTERNET` | Email (SMTP) sending |
| `POST_NOTIFICATIONS` | Show forwarding result notifications |
| Battery optimisation exempt | Reliable background operation |

Tap **Grant Permissions** and **Disable Battery Limit** in the app.

---

## Optional: Sender Filter
Leave blank to forward all SMS. Enter a partial number (e.g. `+1800`) to only forward messages from numbers containing that string.

---

## Architecture

```
SmsReceiver (BroadcastReceiver)
    └─ onReceive() — parse PDUs, check filter
        └─ starts ForwardingService (ForegroundService)
                ├─ forwardEmail()    — JavaMail SMTP on IO thread
                ├─ forwardWhatsApp() — wa.me Intent
                └─ forwardGoogleVoice() — SmsManager
```

- `AppPrefs.kt` — SharedPreferences keys & typed accessors
- `BootReceiver.kt` — ensures the receiver survives reboots
- All network I/O runs on `Dispatchers.IO` via coroutines

---

## Tested on
- Android 8.0 (API 26) — Android 14 (API 34)
