const urlInput = document.getElementById("url");
const keyInput = document.getElementById("key");
const statusEl = document.getElementById("status");
const micStatus = document.getElementById("mic-status");

function setStatus(el, text, kind) {
  el.textContent = text;
  el.className = "status" + (kind ? " " + kind : "");
}

loadSettings().then(({ serverUrl, inboxKey }) => {
  urlInput.value = serverUrl;
  keyInput.value = inboxKey;
});

document.getElementById("toggle-key").addEventListener("click", (e) => {
  const show = keyInput.type === "password";
  keyInput.type = show ? "text" : "password";
  e.target.textContent = show ? "Hide" : "Show";
});

document.getElementById("save").addEventListener("click", () => {
  const url = normalizeUrl(urlInput.value);
  if (!url || !keyInput.value.trim()) {
    setStatus(statusEl, "Enter both the server URL and the key.", "error");
    return;
  }
  // Ask for access first, while we are still inside the click.
  requestHostAccess(url).then(async (granted) => {
    await saveSettings(url, keyInput.value);
    urlInput.value = url;
    if (!granted) {
      setStatus(statusEl, "Saved, but Chrome access to the server was not allowed. Click Save again and allow it.", "error");
      return;
    }
    setStatus(statusEl, "Saved.", "ok");
  });
});

document.getElementById("test").addEventListener("click", () => {
  const url = normalizeUrl(urlInput.value);
  requestHostAccess(url).then(async (granted) => {
    if (!granted) {
      setStatus(statusEl, "Chrome access to the server is needed to test it.", "error");
      return;
    }
    setStatus(statusEl, "Checking…");
    try {
      const info = await ping(url, keyInput.value);
      setStatus(statusEl, `Connected to "${info.name}". ${info.pending} note(s) waiting for the app.`, "ok");
    } catch (err) {
      setStatus(statusEl, err.message || String(err), "error");
    }
  });
});

document.getElementById("mic").addEventListener("click", async () => {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    stream.getTracks().forEach((t) => t.stop());
    setStatus(micStatus, "Microphone allowed. You can record from the side panel now.", "ok");
  } catch (err) {
    setStatus(micStatus, "Microphone was blocked: " + (err.message || err.name), "error");
  }
});

if (new URLSearchParams(location.search).get("mic") === "1") {
  document.getElementById("mic").scrollIntoView();
  setStatus(micStatus, "Click \"Allow microphone\" and accept Chrome's prompt.");
}
