# Resilink Alert (Android)

Small phone app for the Resilink Grid demo. It listens to the Bluetooth advert of the village
node you pick, and when that node reports an alert it rings at full volume and pops a full-screen
message over everything (lock screen and other apps).

Android 10 or newer. Android 12 or newer is best. iPhones cannot do the pop-over.

## Get the APK (no Android Studio needed)
1. On github.com create a new empty repository (private is fine).
2. Unzip this folder. On the repo page choose "uploading an existing file" and drag in EVERYTHING
   inside the folder (app, gradle, .github, gradlew, build.gradle and the rest). Commit.
3. Open the Actions tab. The "Build APK" run starts by itself (about 3 to 5 minutes).
   If nothing starts, open the workflow and press "Run workflow".
4. Open the finished run, scroll to Artifacts, download "resilink-alert-apk", unzip it.
   The file inside is app-debug.apk.
5. Copy the APK to the phone, open it, allow "install unknown apps" for the app you opened it from,
   and tap Install anyway if Play Protect warns.

## First start on the phone
1. Pick your village.
2. Tap the four "Allow" buttons once (Bluetooth and notifications, show over other apps,
   full-screen alerts, keep running in background). Each turns to DONE when it is set.
3. Turn Bluetooth on, tap START WATCHING.
4. Try the Test buttons. They ring at full volume.

## What the nodes must do
The node sketch needs `#define BLE_ALERT 1` (and a big enough partition, e.g. Huge APP).
The advert is: company id 0xFFFF, then the bytes R L 1 v k o d, each digit as ASCII '0' + number.
v = village node id, k = alert (1 danger, 2 evacuate, 3 help), o = node that raised it, d = node that is down.

## Words and colours
Red bulb: EVACUATE NOW, DANGER NEARBY. Yellow bulb: HELP NEEDED, NODE DOWN. Green: ALL CLEAR.
Edit the text in app/src/main/java/com/resilink/alert/Msg.java. Villages are listed at the top of that file.

## Known limits
- The phone must be within about 10 m of the node.
- A phone in Do Not Disturb or with the app force-stopped can stay quiet.
- The app does not restart by itself after the phone reboots. Open it and tap START WATCHING.
