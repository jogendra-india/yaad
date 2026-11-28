---
name: yaad
description: Push a learning/note straight to the user's Yaad spaced-repetition app (inbox at ledgerbal.com/api/yaad; the phone pulls it in on Sync / pull-down). TRIGGER when the user says "add this learning", "yaad pe daal do", "push to yaad", "save to yaad", "add to yaad", "learning add karo", "isko yaad me daal", or otherwise asks to save something they learned for revision. Global — applies in every project.
---

# Yaad: push a learning

Yaad (repo `D:\Repos\yaad`, github `jogendra-india/yaad`) is the user's own Android
revision app. Notes reach it through an inbox on ledgerbal.com: we POST an entry,
the phone pulls it in when the user pulls down on Home or taps **Sync**.

Server URL and key live in `config.json` next to this file (`X-Yaad-Key` header).
Never print the key in chat or put it in a git repo.

## When the user asks to add a learning

Do it directly — no confirmation round-trip. Write the note yourself from the
conversation (or from what they dictate), then push:

```sh
python ~/.claude/skills/yaad/yaad_push.py --title "<short question-style title>" \
  --body-file <scratchpad>/note.html --subject "<deck>" --keywords "k1,k2,k3" [--source URL]
```

Prints `201 {...}` on success. Then tell the user in one line what was added
and to pull down on Home in the app to sync.

### Writing a good note (it's for active recall)

- **Title** — what to recall, ideally a prompt: "Why does Python's GIL not block I/O threads?".
  Only the title shows first in review; the body is revealed on tap, so the title must not give away the answer.
- **Subject** = the deck. Reuse the user's existing decks; run `--pending` or ask if unsure.
  Keep deck names short and consistent (e.g. `Python`, `Android`, `Pumps`, `Git`).
- **Keywords** — 2–5 recall hooks, comma separated.
- **Body** — HTML, short and point-wise. Supported: `<p>`, `<b>`, `<i>`, `<u>`, `<ul>/<ol><li>`, `<br>`.
  No headings, tables, images-in-HTML, code blocks or styles (put code in a `<p>` as plain text).
  Plain text also works (each line becomes a paragraph). Write the body to a file
  in the scratchpad with the Write tool (avoids shell quoting / backslash problems) and pass `--body-file`.
- **Language — Hinglish by default.** Write the title and body in easy, conversational Hinglish
  (Roman script, the way the user talks), unless the user specifically asks for English.
  Keep technical terms in English (GIL, thread, NPSH, commit).
- **Explain it, don't just state it.** The body should leave the user understanding the idea:
  1. one or two lines on what it is and why it matters;
  2. **always one easy example**: a real-life analogy, or a tiny concrete case with numbers or code,
     marked with <b>Example:</b>;
  3. optionally a one-line gotcha or a trick to remember it.
  Sample body: <p><b>GIL</b> matlab Python me ek time pe sirf ek thread Python code chala sakta hai.</p>
  <p><b>Example:</b> ek kitchen, ek hi chulha: 4 cook (threads) hain par khana ek hi bana sakta hai.
  Lekin jab koi cook doodh ubalne ka wait kar raha hai (I/O), chulha dusre ko mil jata hai. Isliye I/O wale kaam threads se fast hote hain, CPU wale nahi.</p>
  <ul><li>CPU-heavy kaam: multiprocessing use karo</li></ul>
- One learning per note. If they give several, push several notes.

### Attachments (optional)

Repeat `--file <path>` once per file; each can have a matching `--kind`
(`photo`, `handwriting`, `audio`, `video`, `document`), given in the same order. Leave
`--kind` out and it is guessed from the extension: images become `photo`, audio `audio`,
video `video`, everything else (PDF, Word, PowerPoint, Excel, txt, md, html, csv...) `document`.
Pass `--kind handwriting` for a sketch or drawing.

```sh
python ~/.claude/skills/yaad/yaad_push.py --title "..." --body-file note.html --subject Pumps \
  --file "D:/path/datasheet.pdf" --file "D:/path/curve.png"
```

- The server accepts only these document types: pdf, doc, docx, ppt, pptx, pps, ppsx, xls, xlsx, odt, ods, odp, rtf, epub, csv, txt, md, html, htm.
  Anything else (.py, .json, .zip...) gets a 400 error, so copy it to `<name>.txt` and attach that.
- The total is capped at about 90 MB per note. Split bigger sets across notes.
- Quote Windows paths and use forward slashes.
- **Yaad opens PDF, Markdown (.md), text (.txt) and HTML (.html) files in its own viewer** (app 1.14+, HTML 1.16+).
  Markdown is formatted with headings, tables, code and lists, and PDFs show every page with pinch zoom.
  HTML is shown as a real page with its own CSS and JS, which suits visual explainers, diagrams, charts,
  interactive quizzes or flashcards.
  Make HTML self-contained (inline CSS/JS, inline SVG for diagrams) so it works offline;
  CDN scripts only load when the phone is online. Design for a phone screen: add
  `<meta name="viewport" content="width=device-width, initial-scale=1">`, use a single column and
  readable 16-18px text, and support dark mode via `prefers-color-scheme`.
  Use this when a learning needs more than the short body: a cheat sheet, a long explanation, a table,
  code or a diagram-heavy PDF. Write a `.md` (or a `.html` for something visual or interactive),
  also in Hinglish with examples, and attach it;
  keep the body as the short recall version and say "Details: attached file".
- Word, PowerPoint, Excel and the rest open in whichever app on the phone handles them.
  Photos and videos show inline.
- Check the `attachments` list in the 201 reply: it should have one item per file.
- If the user asks for a document you have to write yourself (a summary or cheat sheet), write it to the scratchpad and attach it.
- Attach only files the user pointed at or that you made for the note.
- Never Read images yourself (global rule). Reef and email attachments are fine to pass on as files.

## Other commands

- `--ping` → `200 {"name":"personal","pending":N}` checks URL + key.
- `--pending` → entries still waiting on the server (not yet synced to the phone).

## API (for reference)

All with `X-Yaad-Key`: `GET /ping/`, `POST /entries/` (multipart: `title`, `body`,
`subject`, `keywords` (repeat), `source_url`, `files` + `kinds` (repeat, same order)),
`GET /entries/?limit=&offset=`, `GET /attachments/<id>/`, `POST /entries/ack/ {"ids":[...]}`
(deletes them — only the app should ack; don't ack real notes).
