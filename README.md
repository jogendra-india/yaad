# Yaad

**Yaad** is a simple, offline Android app for spaced-repetition revision. Save what you study (photos, handwriting, voice notes, videos, keywords) and Yaad reminds you to revise it at growing intervals: 1, 2, 4, 7, 15, 30, 60 days by default.

**[Download the latest APK](https://github.com/jogendra-india/yaad/releases/latest)**

## Features

- **Rich notes**: title, deck (subject), keywords, formatted text, plus camera/gallery photos, a handwriting pad, voice recordings, videos and documents (PDF, Markdown and text open in Yaad's own viewer, with pinch zoom for PDFs; Word, PowerPoint and the rest open in the app that handles them). Any attachment can be saved back to the phone.
- **Spaced repetition**: each review is rated *Bhool gaya* (forgot), *Thoda* (partly) or *Yaad tha* (remembered). Forgot restarts the schedule, partly repeats the current gap, remembered moves to the next one. Each rating button shows when the note will come back.
- **Active recall**: only the title is shown first; tap to reveal keywords, notes and attachments.
- **Daily digests**: any number of reminder times per day, each with its own toggle, plus a master switch. Notifications have *Revise* and *Snooze 1h* actions and survive reboots.
- **Decks and stars**: browse notes by deck (each note shows when it was added), star important ones, search across titles, keywords and notes, and run a practice session over any deck without touching its schedule.
- **Custom schedules**: set a default interval list and override it per note.
- **Backup**: export or import everything (notes, media, history, settings) as a single `.zip`.
- **Day / Night / System theme**, optional Material You colours, and animated transitions.
- **Inbox sync (optional)**: send notes, screenshots, drawings, voice, video and documents from Chrome with the [browser extension](extension/), then pull down on Home (or tap **Sync**) to bring them in. Needs your own server (see [Sync](#sync)).
- **Offline first**: no account. The app only goes online when you tap *Check for update* or *Sync*.

## Sync

Sync is one way and manual: the browser extension adds notes to an inbox on your server, and the app pulls them in only when you pull down on Home or tap **Sync** (Home top bar, or Settings → Sync). A note is deleted from the server only after the phone has saved it, files included, so an interrupted sync never loses anything; the next tap picks up where it stopped.

Set the same two values in the app (Settings → Sync) and in the extension (its Settings page):

- **Server URL**: the inbox API base, e.g. `https://your-server.example/api/yaad`
- **Key**: the inbox key issued by your server

The server API is five endpoints, all authenticated with the `X-Yaad-Key` header:

| Method | Path | Used by |
|---|---|---|
| `GET` | `/ping/` | both, to test the URL and key |
| `POST` | `/entries/` | extension: multipart `title`, `body`, `subject`, `keywords[]`, `source_url`, `files[]`, `kinds[]` (`photo`, `handwriting`, `audio`, `video`, `document`) |
| `GET` | `/entries/?limit=&offset=` | app: waiting entries, oldest first |
| `GET` | `/attachments/<id>/` | app: one file |
| `POST` | `/entries/ack/` | app: `{"ids": [...]}` once saved; the server deletes them |

## Tech

Kotlin, Jetpack Compose (Material 3), Navigation Compose, Room, AlarmManager (inexact, doze-friendly), Coil (images and video frames), PdfRenderer (PDF pages), [marked](https://github.com/markedjs/marked) (MIT, bundled in `app/src/main/assets/viewer/`) for Markdown in an offline WebView. Min SDK 26, target SDK 35.

## Build

```sh
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # scheduler unit tests
```

Open the project in Android Studio (JDK 17+; the bundled JBR works) to run it on a device or emulator.
