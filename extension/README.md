# Yaad Inbox (Chrome extension)

Capture notes in Chrome and send them to your Yaad inbox. Nothing syncs on its own: a note goes to the server when you click **Save to inbox**, and reaches your phone when you tap **Sync** in the Yaad app.

## Install

1. Open `chrome://extensions` and turn on **Developer mode**.
2. Click **Load unpacked** and pick this `extension/` folder.
3. Open the extension's **Settings** (right-click its toolbar icon → Options), enter your **Server URL** (e.g. `https://your-server.example/api/yaad`) and **Key**, click **Save** and allow access to the server when Chrome asks. **Test connection** confirms it works.
4. Optional: click **Allow microphone** on the same page once, so voice notes can be recorded from the side panel.

## Use

Click the toolbar icon to open the side panel, or right-click a selection, image, link or page and choose **Add to Yaad**.

- **Title, Body, Subject, Keywords, Source link.** Subject suggests the ones you used before. The source link is the page the note came from (filled in by right-click → Add to Yaad); the app adds it at the end of the note. Leave it empty if you don't need it.
- **Body formatting**, the same as the app: **B** / *I* / U (or Ctrl+B / Ctrl+I / Ctrl+U), bullet and numbered lists. Typing `- ` or `1. ` at the start of a line starts a list. Pasted text keeps bold, italic, underline and lists.
- **Attachments**, the same kinds the app has:
  - **Photo**: press Ctrl+V with a screenshot or copied image, drop an image, or use **Add file**.
  - **Video**: copy a video file in your file manager and press Ctrl+V, drop it, or use **Add file**.
  - **Handwriting**: **Draw** opens a white canvas (mouse, touch or pen) with colours, widths and undo.
  - **Voice**: **Record voice** records from the microphone; audio files can be pasted or dropped too.
  - **Document**: PDF, Word, PowerPoint, Excel, OpenDocument, RTF, EPUB, CSV, TXT, Markdown. Copy the file and press Ctrl+V, drop it, or use **Add file**. On the phone it opens in whichever app handles it.
- Up to 90 MB of attachments per note.

The text you type is kept if the panel closes; attachments are not.
