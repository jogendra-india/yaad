// Side panel: compose one note with attachments and send it to the inbox.
// Nothing is sent until "Save to inbox" is clicked.

const $ = (id) => document.getElementById(id);
const fields = {
  title: $("title"),
  subject: $("subject"),
  keywords: $("keywords"),
  source: $("source"),
};
const editor = $("body"); // rich text, see editor.js
RichEditor.init(editor);
const statusEl = $("status");
const recordStatus = $("record-status");
const progress = $("progress");
const saveButton = $("save");

const SUBJECT_HISTORY_MAX = 50;
// Documents the server accepts (by extension). PDF, Markdown, text and HTML open in Yaad itself; the rest in another app.
const DOCUMENT_EXTENSIONS = [
  "pdf", "doc", "docx", "ppt", "pptx", "pps", "ppsx", "xls", "xlsx",
  "csv", "txt", "md", "html", "htm", "rtf", "odt", "ods", "odp", "epub",
];
const EXTENSION_KINDS = {
  jpg: "photo", jpeg: "photo", png: "photo", gif: "photo", webp: "photo", bmp: "photo", heic: "photo",
  mp4: "video", mov: "video", webm: "video", mkv: "video", "3gp": "video",
  mp3: "audio", m4a: "audio", aac: "audio", ogg: "audio", wav: "audio", flac: "audio", opus: "audio",
  ...Object.fromEntries(DOCUMENT_EXTENSIONS.map((ext) => [ext, "document"])),
};
const extensionOf = (name) => {
  const parts = (name || "").split(".");
  return parts.length > 1 ? parts.pop().toLowerCase() : "";
};
// Files copied from the file manager sometimes arrive without a type; the
// server checks it, so fill it in from the extension.
const EXTENSION_MIME = {
  jpg: "image/jpeg", jpeg: "image/jpeg", png: "image/png", gif: "image/gif", webp: "image/webp",
  bmp: "image/bmp", heic: "image/heic",
  mp4: "video/mp4", mov: "video/quicktime", webm: "video/webm", mkv: "video/x-matroska", "3gp": "video/3gpp",
  mp3: "audio/mpeg", m4a: "audio/mp4", aac: "audio/aac", ogg: "audio/ogg", wav: "audio/wav",
  flac: "audio/flac", opus: "audio/ogg",
};
const KIND_ICON = { audio: "🎤", video: "🎬", document: "📄" };

let settings = { serverUrl: "", inboxKey: "" };
let items = []; // { id, file, kind, previewUrl }
let nextId = 1;
let uploading = false;

function setStatus(el, text, kind) {
  el.textContent = text;
  el.className = "status" + (kind ? " " + kind : "");
}

// --- settings, subjects, draft ------------------------------------------------

async function refreshSettings() {
  settings = await loadSettings();
  $("setup").hidden = Boolean(settings.serverUrl && settings.inboxKey);
}

async function loadSubjects() {
  const { subjects = [] } = await chrome.storage.local.get("subjects");
  $("subjects").replaceChildren(
    ...subjects.map((s) => Object.assign(document.createElement("option"), { value: s }))
  );
}

async function rememberSubject(subject) {
  if (!subject) return;
  const { subjects = [] } = await chrome.storage.local.get("subjects");
  const next = [subject, ...subjects.filter((s) => s !== subject)].slice(0, SUBJECT_HISTORY_MAX);
  await chrome.storage.local.set({ subjects: next });
  loadSubjects();
}

// Text survives the panel being closed; attachments do not.
function saveDraft() {
  const draft = Object.fromEntries(Object.entries(fields).map(([k, el]) => [k, el.value]));
  draft.body = editor.innerHTML;
  chrome.storage.session.set({ draft });
}

async function restoreDraft() {
  const { draft } = await chrome.storage.session.get("draft");
  if (!draft) return;
  for (const [k, el] of Object.entries(fields)) if (draft[k]) el.value = draft[k];
  if (draft.body) RichEditor.setHtml(editor, draft.body);
}

Object.values(fields).forEach((el) => el.addEventListener("input", saveDraft));
editor.addEventListener("input", saveDraft);

chrome.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && (changes.serverUrl || changes.inboxKey)) refreshSettings();
  if (area === "session" && changes.pendingClip && changes.pendingClip.newValue) takePendingClip();
});

// --- attachments --------------------------------------------------------------

function kindFor(file) {
  const ext = extensionOf(file.name);
  if (DOCUMENT_EXTENSIONS.includes(ext)) return "document";
  const type = (file.type || "").toLowerCase();
  if (type.startsWith("image/")) return "photo";
  if (type.startsWith("video/")) return "video";
  if (type.startsWith("audio/")) return "audio";
  return EXTENSION_KINDS[ext] || null;
}

function addFile(file, kind) {
  if (!file.type) {
    const mime = EXTENSION_MIME[extensionOf(file.name)];
    if (mime) file = new File([file], file.name, { type: mime, lastModified: file.lastModified });
  }
  kind = kind || kindFor(file);
  if (!kind) {
    setStatus(
      statusEl,
      `"${file.name}" can't be attached. Use an image, audio, video or a document (${DOCUMENT_EXTENSIONS.join(", ")}).`,
      "error"
    );
    return false;
  }
  items.push({ id: nextId++, file, kind, previewUrl: URL.createObjectURL(file) });
  render();
  return true;
}

function removeItem(id) {
  const item = items.find((i) => i.id === id);
  if (item) URL.revokeObjectURL(item.previewUrl);
  items = items.filter((i) => i.id !== id);
  render();
}

function render() {
  const list = $("attachments");
  list.replaceChildren(
    ...items.map((item) => {
      const card = document.createElement("div");
      card.className = "attachment";
      let preview;
      if (item.kind === "photo" || item.kind === "handwriting") {
        preview = Object.assign(document.createElement("img"), { src: item.previewUrl, alt: item.file.name });
      } else if (item.kind === "video") {
        preview = Object.assign(document.createElement("video"), { src: item.previewUrl, muted: true, preload: "metadata" });
      } else if (item.kind === "document") {
        preview = document.createElement("div");
        preview.className = "audio doc";
        preview.append(
          Object.assign(document.createElement("span"), { textContent: KIND_ICON.document }),
          Object.assign(document.createElement("span"), { className: "doc-name", textContent: item.file.name, title: item.file.name })
        );
      } else {
        preview = Object.assign(document.createElement("div"), { className: "audio", textContent: KIND_ICON.audio });
      }
      const meta = document.createElement("div");
      meta.className = "meta";
      meta.append(
        Object.assign(document.createElement("span"), { textContent: item.kind === "document" ? extensionOf(item.file.name).toUpperCase() : item.kind }),
        Object.assign(document.createElement("span"), { textContent: formatBytes(item.file.size) })
      );
      const remove = Object.assign(document.createElement("button"), {
        className: "remove",
        type: "button",
        textContent: "✕",
        title: "Remove",
      });
      remove.setAttribute("aria-label", "Remove " + item.kind);
      remove.addEventListener("click", () => removeItem(item.id));
      card.append(preview, meta, remove);
      return card;
    })
  );
}

function filesFrom(dataTransfer) {
  if (!dataTransfer) return [];
  const files = Array.from(dataTransfer.files || []);
  if (files.length) return files;
  return Array.from(dataTransfer.items || [])
    .filter((it) => it.kind === "file")
    .map((it) => it.getAsFile())
    .filter(Boolean);
}

document.addEventListener("paste", (e) => {
  const files = filesFrom(e.clipboardData);
  if (!files.length) return; // plain text paste: let the field handle it
  e.preventDefault();
  let added = 0;
  files.forEach((f, i) => {
    // Screenshots arrive as "image.png"; give them a useful name.
    const named = f.name && f.name !== "image.png" ? f : new File([f], `pasted-${Date.now()}-${i}.${(f.type.split("/")[1] || "png")}`, { type: f.type });
    if (addFile(named)) added++;
  });
  if (added) setStatus(statusEl, `Attached ${added} file${added > 1 ? "s" : ""}.`, "ok");
});

const dropzone = $("dropzone");
["dragenter", "dragover"].forEach((type) =>
  document.addEventListener(type, (e) => {
    if (!Array.from(e.dataTransfer?.types || []).includes("Files")) return;
    e.preventDefault();
    dropzone.classList.add("over");
  })
);
["dragleave", "drop"].forEach((type) =>
  document.addEventListener(type, () => dropzone.classList.remove("over"))
);
document.addEventListener("drop", (e) => {
  const files = filesFrom(e.dataTransfer);
  if (!files.length) return;
  e.preventDefault();
  files.forEach((f) => addFile(f));
});

$("pick").addEventListener("click", () => $("file-input").click());
$("file-input").addEventListener("change", (e) => {
  Array.from(e.target.files).forEach((f) => addFile(f));
  e.target.value = "";
});

// --- right-click "Add to Yaad" -------------------------------------------------

async function takePendingClip() {
  const { pendingClip: clip } = await chrome.storage.session.get("pendingClip");
  if (!clip) return;
  await chrome.storage.session.remove("pendingClip");
  if (clip.text) {
    RichEditor.appendText(editor, clip.text);
  }
  if (!fields.title.value && clip.title && !clip.imageUrl) fields.title.value = clip.title.slice(0, 300);
  if (!fields.source.value && /^https?:/i.test(clip.sourceUrl)) fields.source.value = clip.sourceUrl;
  saveDraft();
  if (clip.imageUrl) await attachImageFromUrl(clip.imageUrl);
}

async function attachImageFromUrl(url) {
  try {
    if (url.startsWith("data:")) {
      const blob = await (await fetch(url)).blob();
      addFile(new File([blob], `image-${Date.now()}.${blob.type.split("/")[1] || "png"}`, { type: blob.type }));
      return;
    }
    const pattern = originPattern(url);
    const allowed = pattern && (await chrome.permissions.contains({ origins: [pattern] }));
    if (!allowed) throw new Error("no access");
    const response = await fetch(url);
    if (!response.ok) throw new Error("HTTP " + response.status);
    const blob = await response.blob();
    const name = decodeURIComponent(new URL(url).pathname.split("/").pop() || "") || `image-${Date.now()}.png`;
    addFile(new File([blob], name, { type: blob.type }));
  } catch (e) {
    setStatus(statusEl, "Couldn't fetch that image directly. Right-click it → Copy image, then press Ctrl+V here.", "error");
  }
}

// --- voice recording ------------------------------------------------------------

let recorder = null;
let recordTimer = null;

async function startRecording() {
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  } catch (err) {
    setStatus(recordStatus, "Microphone not allowed yet. Opening Settings: click \"Allow microphone\" there, then record here again.", "error");
    chrome.tabs.create({ url: chrome.runtime.getURL("options.html?mic=1") });
    return;
  }
  const mime = ["audio/webm;codecs=opus", "audio/webm", "audio/ogg;codecs=opus"].find((m) => MediaRecorder.isTypeSupported(m)) || "";
  recorder = new MediaRecorder(stream, mime ? { mimeType: mime } : undefined);
  const chunks = [];
  const started = Date.now();
  recorder.ondataavailable = (e) => e.data.size && chunks.push(e.data);
  recorder.onstop = () => {
    stream.getTracks().forEach((t) => t.stop());
    clearInterval(recordTimer);
    const type = (recorder.mimeType || "audio/webm").split(";")[0];
    const ext = type.includes("ogg") ? "ogg" : "webm";
    const blob = new Blob(chunks, { type });
    recorder = null;
    $("record").textContent = "Record voice";
    if (blob.size) {
      addFile(new File([blob], `voice-${Date.now()}.${ext}`, { type }), "audio");
      setStatus(recordStatus, "Voice note attached.", "ok");
    } else {
      setStatus(recordStatus, "Nothing was recorded.", "error");
    }
  };
  recorder.start(1000);
  $("record").textContent = "Stop";
  recordTimer = setInterval(() => {
    const s = Math.floor((Date.now() - started) / 1000);
    recordStatus.className = "status recording";
    recordStatus.textContent = `● Recording ${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
  }, 250);
}

$("record").addEventListener("click", () => {
  if (recorder) recorder.stop();
  else startRecording();
});

// --- handwriting ------------------------------------------------------------------

const overlay = $("draw-overlay");
const canvas = $("canvas");
const ctx = canvas.getContext("2d");
let strokes = [];
let current = null;
let penColor = "#1d1b18";

function fitCanvas() {
  const ratio = window.devicePixelRatio || 1;
  const rect = canvas.getBoundingClientRect();
  canvas.width = Math.round(rect.width * ratio);
  canvas.height = Math.round(rect.height * ratio);
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  redraw();
}

function drawStroke(s) {
  ctx.strokeStyle = s.color;
  ctx.lineWidth = s.width;
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.beginPath();
  s.points.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
  if (s.points.length === 1) ctx.lineTo(s.points[0][0] + 0.1, s.points[0][1]);
  ctx.stroke();
}

function redraw() {
  const rect = canvas.getBoundingClientRect();
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, rect.width, rect.height);
  strokes.forEach(drawStroke);
}

function point(e) {
  const rect = canvas.getBoundingClientRect();
  return [e.clientX - rect.left, e.clientY - rect.top];
}

canvas.addEventListener("pointerdown", (e) => {
  canvas.setPointerCapture(e.pointerId);
  const pressure = e.pointerType === "pen" && e.pressure ? 0.5 + e.pressure : 1;
  current = { color: penColor, width: Number($("pen-width").value) * pressure, points: [point(e)] };
  strokes.push(current);
  redraw();
});
canvas.addEventListener("pointermove", (e) => {
  if (!current) return;
  current.points.push(point(e));
  redraw();
});
["pointerup", "pointercancel"].forEach((t) => canvas.addEventListener(t, () => (current = null)));

document.querySelectorAll(".pen").forEach((btn) =>
  btn.addEventListener("click", () => {
    document.querySelectorAll(".pen").forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    penColor = btn.dataset.color;
  })
);

$("draw").addEventListener("click", () => {
  strokes = [];
  overlay.hidden = false;
  fitCanvas();
});
$("undo").addEventListener("click", () => {
  strokes.pop();
  redraw();
});
$("erase-all").addEventListener("click", () => {
  strokes = [];
  redraw();
});
$("draw-cancel").addEventListener("click", () => (overlay.hidden = true));
$("draw-done").addEventListener("click", () => {
  if (!strokes.length) {
    overlay.hidden = true;
    return;
  }
  canvas.toBlob((blob) => {
    addFile(new File([blob], `handwriting-${Date.now()}.png`, { type: "image/png" }), "handwriting");
    overlay.hidden = true;
  }, "image/png");
});
window.addEventListener("resize", () => {
  if (!overlay.hidden) fitCanvas();
});

// --- save -------------------------------------------------------------------------

function clearForm() {
  Object.values(fields).forEach((el) => (el.value = ""));
  RichEditor.clear(editor);
  items.forEach((i) => URL.revokeObjectURL(i.previewUrl));
  items = [];
  render();
  chrome.storage.session.remove("draft");
}

function errorText(xhr) {
  if (xhr.status === 0) return "Couldn't reach the server. Check the URL and your internet.";
  if (xhr.status === 401) return "Wrong key. Check it in Settings.";
  if (xhr.status === 404) return "Wrong URL (404). It should end in /api/yaad";
  if (xhr.status === 413) return "Too large for the server. Send fewer or smaller files.";
  if (xhr.status === 429) return "Too many requests. Wait a minute and try again.";
  try {
    const data = JSON.parse(xhr.responseText);
    const parts = [];
    const walk = (v) => (Array.isArray(v) ? v.forEach(walk) : v && typeof v === "object" ? Object.values(v).forEach(walk) : parts.push(String(v)));
    walk(data);
    if (parts.length) return parts.join(" ");
  } catch (e) {
    /* not JSON */
  }
  return "Server error: HTTP " + xhr.status;
}

function upload() {
  const form = new FormData();
  form.append("title", fields.title.value.trim());
  form.append("body", RichEditor.isEmpty(editor) ? "" : RichEditor.getHtml(editor));
  form.append("subject", fields.subject.value.trim());
  fields.keywords.value
    .split(",")
    .map((k) => k.trim())
    .filter(Boolean)
    .forEach((k) => form.append("keywords", k));
  form.append("source_url", fields.source.value.trim());
  items.forEach((item) => {
    form.append("files", item.file, item.file.name);
    form.append("kinds", item.kind);
  });

  const xhr = new XMLHttpRequest();
  xhr.open("POST", normalizeUrl(settings.serverUrl) + "/entries/");
  xhr.setRequestHeader(KEY_HEADER, settings.inboxKey);
  xhr.upload.onprogress = (e) => {
    if (e.lengthComputable) progress.firstElementChild.style.width = (100 * e.loaded) / e.total + "%";
  };
  xhr.onloadend = () => {
    uploading = false;
    saveButton.disabled = false;
    progress.hidden = true;
    if (xhr.status === 201) {
      rememberSubject(fields.subject.value.trim());
      clearForm();
      setStatus(statusEl, "Saved. Tap Sync in the Yaad app to get it.", "ok");
    } else {
      setStatus(statusEl, errorText(xhr), "error");
    }
  };
  uploading = true;
  saveButton.disabled = true;
  progress.hidden = false;
  progress.firstElementChild.style.width = "0";
  setStatus(statusEl, "Saving…");
  xhr.send(form);
}

saveButton.addEventListener("click", () => {
  if (uploading) return;
  if (!settings.serverUrl || !settings.inboxKey) {
    setStatus(statusEl, "Set the server URL and key in Settings first.", "error");
    return;
  }
  if (!fields.title.value.trim() && RichEditor.isEmpty(editor) && !items.length) {
    setStatus(statusEl, "Add a title, some text or an attachment.", "error");
    return;
  }
  const total = items.reduce((sum, i) => sum + i.file.size, 0);
  if (total > MAX_TOTAL_BYTES) {
    setStatus(statusEl, `Attachments are ${formatBytes(total)}; the limit is ${formatBytes(MAX_TOTAL_BYTES)} per note.`, "error");
    return;
  }
  // Inside the click, before any await, so Chrome can show its prompt if needed.
  requestHostAccess(settings.serverUrl).then((granted) => {
    if (!granted) {
      setStatus(statusEl, "Chrome needs access to your server to send the note. Click Save again and allow it.", "error");
      return;
    }
    upload();
  });
});

$("clear").addEventListener("click", () => {
  clearForm();
  setStatus(statusEl, "");
});

// --- start ------------------------------------------------------------------------

(async () => {
  await refreshSettings();
  await loadSubjects();
  await restoreDraft();
  await takePendingClip();
  fields.title.focus();
})();
