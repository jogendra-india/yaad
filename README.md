# Yaad

**Yaad** is a simple, offline Android app for spaced-repetition revision. Save what you study (photos, handwriting, voice notes, videos, keywords) and Yaad reminds you to revise it at growing intervals: 1, 2, 4, 7, 15, 30, 60 days by default.

**[Download the latest APK](https://github.com/jogendra-india/yaad/releases/latest)**

## Features

- **Rich notes**: title, deck (subject), keywords, short text, plus camera/gallery photos, a handwriting pad, voice recordings and videos.
- **Spaced repetition**: each review is rated *Bhool gaya* (forgot), *Thoda* (partly) or *Yaad tha* (remembered). Forgot restarts the schedule, partly repeats the current gap, remembered moves to the next one. Each rating button shows when the note will come back.
- **Active recall**: only the title is shown first; tap to reveal keywords, notes and attachments.
- **Daily digests**: any number of reminder times per day, each with its own toggle, plus a master switch. Notifications have *Revise* and *Snooze 1h* actions and survive reboots.
- **Decks and stars**: browse notes by deck, star important ones, search across titles, keywords and notes, and run a practice session over any deck without touching its schedule.
- **Custom schedules**: set a default interval list and override it per note.
- **Backup**: export or import everything (notes, media, history, settings) as a single `.zip`.
- **Day / Night / System theme**, optional Material You colours, and animated transitions.
- **Fully offline**: no account, no network permission.

## Tech

Kotlin, Jetpack Compose (Material 3), Navigation Compose, Room, AlarmManager (inexact, doze-friendly), Coil (images and video frames). Min SDK 26, target SDK 35.

## Build

```sh
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # scheduler unit tests
```

Open the project in Android Studio (JDK 17+; the bundled JBR works) to run it on a device or emulator.
