// Rich text body for the side panel. It writes the same HTML the Yaad app's
// editor stores: <p>, <br>, <b>, <i>, <u>, <ul>/<ol> with <li>. Anything else
// (pasted web pages, Google Docs spans, etc.) is reduced to that set.

const RichEditor = (() => {
  const INLINE_TAGS = { B: "b", STRONG: "b", I: "i", EM: "i", U: "u", INS: "u" };
  const SKIP_TAGS = new Set(["SCRIPT", "STYLE", "NOSCRIPT", "TEMPLATE", "HEAD", "META", "TITLE", "SVG", "IFRAME", "OBJECT"]);
  const LIST_TAGS = new Set(["UL", "OL"]);
  const BLOCK_TAGS = new Set([
    "P", "DIV", "H1", "H2", "H3", "H4", "H5", "H6", "BLOCKQUOTE", "PRE", "SECTION", "ARTICLE",
    "HEADER", "FOOTER", "MAIN", "ASIDE", "NAV", "FIGURE", "FIGCAPTION", "TABLE", "TBODY", "THEAD",
    "TR", "TD", "TH", "DL", "DT", "DD", "HR", "ADDRESS", "BODY",
  ]);

  const escapeText = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

  // Formatting expressed as CSS (Google Docs, Word online) instead of tags.
  function styleWraps(el) {
    const st = el.style;
    if (!st) return [];
    const wraps = [];
    const weight = st.fontWeight;
    if (weight === "bold" || weight === "bolder" || Number(weight) >= 600) wraps.push("b");
    if (st.fontStyle === "italic") wraps.push("i");
    if ((st.textDecoration || st.textDecorationLine || "").includes("underline")) wraps.push("u");
    return wraps;
  }

  function wrap(html, tags) {
    if (!html) return "";
    return tags.reduceRight((inner, t) => `<${t}>${inner}</${t}>`, html);
  }

  // Inline content of a node; nested blocks become line breaks.
  function inline(node) {
    if (node.nodeType === Node.TEXT_NODE) return escapeText(node.nodeValue.replace(/ /g, " "));
    if (node.nodeType !== Node.ELEMENT_NODE || SKIP_TAGS.has(node.tagName)) return "";
    if (node.tagName === "BR") return "<br>";
    const inner = Array.from(node.childNodes).map(inline).join("");
    const tags = [];
    if (INLINE_TAGS[node.tagName]) tags.push(INLINE_TAGS[node.tagName]);
    if (node.tagName === "SPAN" || node.tagName === "FONT" || node.tagName === "A") tags.push(...styleWraps(node));
    let html = wrap(inner, [...new Set(tags)]);
    if (BLOCK_TAGS.has(node.tagName) || LIST_TAGS.has(node.tagName) || node.tagName === "LI") html += "<br>";
    return html;
  }

  const trimBreaks = (html) => html.replace(/^(\s|<br>)+|(\s|<br>)+$/g, "");
  const hasText = (html) => trimBreaks(html.replace(/<[^>]+>/g, "")).trim().length > 0;

  // One list. Nested lists are flattened into sibling items: the app's lists are one level.
  function list(el) {
    const tag = el.tagName === "OL" ? "ol" : "ul";
    const items = [];
    const walk = (listEl) => {
      Array.from(listEl.childNodes).forEach((child) => {
        if (child.nodeType !== Node.ELEMENT_NODE) return;
        if (LIST_TAGS.has(child.tagName)) return walk(child);
        const nested = [];
        const content = Array.from(child.childNodes)
          .filter((n) => !(n.nodeType === Node.ELEMENT_NODE && LIST_TAGS.has(n.tagName) && nested.push(n)))
          .map(inline)
          .join("");
        const text = trimBreaks(content);
        if (hasText(text)) items.push(`<li>${text}</li>`);
        nested.forEach(walk);
      });
    };
    walk(el);
    return items.length ? `<${tag}>${items.join("")}</${tag}>` : "";
  }

  function sanitize(html) {
    const doc = new DOMParser().parseFromString(`<body>${html || ""}</body>`, "text/html");
    const out = [];
    let run = "";
    const flush = () => {
      const p = trimBreaks(run);
      if (hasText(p)) out.push(`<p>${p}</p>`);
      run = "";
    };
    const top = (node) => {
      if (node.nodeType === Node.ELEMENT_NODE) {
        if (SKIP_TAGS.has(node.tagName)) return;
        if (LIST_TAGS.has(node.tagName)) {
          flush();
          const l = list(node);
          if (l) out.push(l);
          return;
        }
        if (BLOCK_TAGS.has(node.tagName) || node.tagName === "LI") {
          flush();
          Array.from(node.childNodes).forEach(top);
          flush();
          return;
        }
        // An inline element that contains blocks (e.g. <a><div>…</div></a>).
        if (node.querySelector && node.querySelector("p,div,ul,ol,li,h1,h2,h3,h4,h5,h6,table")) {
          Array.from(node.childNodes).forEach(top);
          return;
        }
      }
      run += inline(node);
    };
    Array.from(doc.body.childNodes).forEach(top);
    flush();
    return out.join("");
  }

  function init(editor) {
    document.execCommand("defaultParagraphSeparator", false, "p");
    document.execCommand("styleWithCSS", false, false);

    // "- " / "* " / "1. " at the start of a line turns it into a list, like the app.
    editor.addEventListener("input", (e) => {
      if (e.inputType !== "insertText" || (e.data !== " " && e.data !== " ")) return;
      const sel = window.getSelection();
      if (!sel.rangeCount || !sel.isCollapsed) return;
      const node = sel.anchorNode;
      if (!node || node.nodeType !== Node.TEXT_NODE || !editor.contains(node)) return;
      if (node.parentElement.closest("li")) return;
      const before = node.nodeValue.slice(0, sel.anchorOffset);
      const match = before.match(/^([-*•]|1[.)])[\s ]$/);
      if (!match) return;
      // Only at the very start of its line.
      let prev = node.previousSibling;
      while (prev && prev.nodeType === Node.TEXT_NODE && !prev.nodeValue) prev = prev.previousSibling;
      if (prev && prev.nodeName !== "BR") return;
      const range = document.createRange();
      range.setStart(node, 0);
      range.setEnd(node, sel.anchorOffset);
      sel.removeAllRanges();
      sel.addRange(range);
      document.execCommand("delete");
      document.execCommand(match[1].startsWith("1") ? "insertOrderedList" : "insertUnorderedList");
    });

    // Text pastes keep bold/italic/underline/lists and drop everything else.
    // File pastes are left to the panel's attachment handler.
    editor.addEventListener("paste", (e) => {
      const data = e.clipboardData;
      if (!data) return;
      const hasFiles = Array.from(data.items || []).some((it) => it.kind === "file") || (data.files && data.files.length);
      if (hasFiles) return;
      const html = data.getData("text/html");
      const text = data.getData("text/plain");
      e.preventDefault();
      if (html) document.execCommand("insertHTML", false, sanitize(html) || escapeText(text));
      else if (text) document.execCommand("insertText", false, text);
    });

    // Toolbar: keep the caret in the editor, show which styles are on.
    const buttons = Array.from(document.querySelectorAll(".fmt"));
    buttons.forEach((btn) => {
      btn.addEventListener("mousedown", (e) => e.preventDefault());
      btn.addEventListener("click", () => {
        editor.focus();
        document.execCommand(btn.dataset.cmd);
        refresh();
      });
    });
    function refresh() {
      const sel = window.getSelection();
      const inside = sel.rangeCount && editor.contains(sel.anchorNode);
      buttons.forEach((btn) => btn.classList.toggle("active", Boolean(inside && document.queryCommandState(btn.dataset.cmd))));
    }
    document.addEventListener("selectionchange", refresh);
  }

  return {
    init,
    sanitize,
    escapeText,
    getHtml: (editor) => sanitize(editor.innerHTML),
    isEmpty: (editor) => !editor.innerText.trim(),
    setHtml: (editor, html) => {
      editor.innerHTML = sanitize(html);
    },
    appendText: (editor, text) => {
      const paragraphs = text.split(/\r?\n/).filter((line) => line.trim()).map((line) => `<p>${escapeText(line)}</p>`);
      editor.insertAdjacentHTML("beforeend", paragraphs.join(""));
    },
    clear: (editor) => {
      editor.innerHTML = "";
    },
  };
})();
