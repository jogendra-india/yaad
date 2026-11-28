// Shared by the side panel and the options page.

const KEY_HEADER = "X-Yaad-Key";
// Must stay under the server's per-request limit.
const MAX_TOTAL_BYTES = 90 * 1024 * 1024;

function normalizeUrl(raw) {
  let url = (raw || "").trim().replace(/\/+$/, "");
  if (!url) return "";
  if (!/^https?:\/\//i.test(url)) url = "https://" + url;
  return url;
}

function originPattern(url) {
  try {
    return new URL(url).origin + "/*";
  } catch (e) {
    return null;
  }
}

async function loadSettings() {
  const { serverUrl = "", inboxKey = "" } = await chrome.storage.local.get(["serverUrl", "inboxKey"]);
  return { serverUrl, inboxKey };
}

async function saveSettings(serverUrl, inboxKey) {
  await chrome.storage.local.set({ serverUrl: normalizeUrl(serverUrl), inboxKey: (inboxKey || "").trim() });
}

/**
 * Asks Chrome for access to the server's origin. Call it straight from a click
 * handler, before any await: Chrome only shows the prompt during a user gesture.
 */
function requestHostAccess(url) {
  const pattern = originPattern(url);
  if (!pattern) return Promise.resolve(false);
  return chrome.permissions.request({ origins: [pattern] });
}

async function ping(serverUrl, inboxKey) {
  const response = await fetch(normalizeUrl(serverUrl) + "/ping/", {
    headers: { [KEY_HEADER]: (inboxKey || "").trim() },
  });
  if (response.status === 401) throw new Error("Wrong key.");
  if (response.status === 404) throw new Error("Wrong URL (404). It should end in /api/yaad");
  if (!response.ok) throw new Error("Server error: HTTP " + response.status);
  return response.json();
}

function formatBytes(n) {
  if (n < 1024) return n + " B";
  if (n < 1024 * 1024) return (n / 1024).toFixed(0) + " KB";
  return (n / 1024 / 1024).toFixed(1) + " MB";
}
