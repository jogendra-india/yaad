// Toolbar button opens the side panel; right-click menu sends the selection,
// an image or the page into a new note there.

const MENU_ID = "yaad-add";

chrome.runtime.onInstalled.addListener(() => {
  chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true });
  chrome.contextMenus.create({
    id: MENU_ID,
    title: "Add to Yaad",
    contexts: ["selection", "image", "page", "link"],
  });
});

chrome.contextMenus.onClicked.addListener((info, tab) => {
  if (info.menuItemId !== MENU_ID) return;
  const clip = {
    text: info.selectionText || "",
    imageUrl: info.mediaType === "image" ? info.srcUrl || "" : "",
    sourceUrl: info.linkUrl || info.pageUrl || (tab && tab.url) || "",
    title: (tab && tab.title) || "",
    at: Date.now(),
  };
  // Opening the panel must happen inside the click, before any await.
  if (tab && tab.windowId !== undefined) {
    chrome.sidePanel.open({ windowId: tab.windowId });
  }
  chrome.storage.session.set({ pendingClip: clip });
});
