(function () {
  if (window.__spoonGeckoDropInit) return;
  window.__spoonGeckoDropInit = true;

  const PORT_NAME = "spoongeckoDrop";
  const MAX_DROP_BYTES = 50 * 1024 * 1024;
  const pendingDrops = new Map();
  let port = null;
  let reconnectTimer = null;

  function scheduleReconnect() {
    if (reconnectTimer) return;
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      connect();
    }, 400);
  }

  function connect() {
    if (port) return;
    try {
      port = browser.runtime.connectNative(PORT_NAME);
      port.onMessage.addListener(handleMessage);
      port.onDisconnect.addListener(() => {
        port = null;
        pendingDrops.clear();
        scheduleReconnect();
      });
    } catch (e) {
      port = null;
      scheduleReconnect();
    }
  }

  window.addEventListener("pageshow", () => {
    if (!port) connect();
  });

  function decodeBase64ToBytes(b64) {
    const bin = atob(b64);
    const len = bin.length;
    const out = new Uint8Array(len);
    for (let i = 0; i < len; i++) {
      out[i] = bin.charCodeAt(i);
    }
    return out;
  }

  function pageWindow(win) {
    return (win && win.wrappedJSObject) ? win.wrappedJSObject : win;
  }

  function canClone(win) {
    return typeof cloneInto === "function" && !!win && !!win.wrappedJSObject;
  }

  function unwrapElement(el) {
    return (el && el.wrappedJSObject) ? el.wrappedJSObject : el;
  }

  function dispatchInPage(el, event) {
    const rawEl = unwrapElement(el);
    try {
      return rawEl.dispatchEvent(event);
    } catch (e) {
      return el.dispatchEvent(event);
    }
  }

  function buildFilesForWindow(win, metaList, drop) {
    const pWin = pageWindow(win);
    const useClone = canClone(win);
    const FileCtor = (pWin && pWin.File) ? pWin.File : File;
    const BlobCtor = (pWin && pWin.Blob) ? pWin.Blob : Blob;
    const ArrayCtor = (pWin && pWin.Array) ? pWin.Array : Array;
    const built = [];

    for (let i = 0; i < metaList.length; i++) {
      const meta = metaList[i] || {};
      const parts = drop ? (drop.get(i) || []) : [];
      const mime = meta.mime || "application/octet-stream";
      const fileName = meta.name || "file";

      try {
        const pageParts = new ArrayCtor();
        for (let j = 0; j < parts.length; j++) {
          pageParts.push(useClone ? cloneInto(parts[j], pWin) : parts[j]);
        }
        const optsPlain = { type: mime, lastModified: Date.now() };
        const opts = useClone ? cloneInto(optsPlain, pWin) : optsPlain;
        let file = null;
        try {
          file = new FileCtor(pageParts, fileName, opts);
        } catch (err) {
          const blobOptsPlain = { type: mime };
          const blobOpts = useClone ? cloneInto(blobOptsPlain, pWin) : blobOptsPlain;
          const blob = new BlobCtor(pageParts, blobOpts);
          const blobArr = new ArrayCtor();
          blobArr.push(blob);
          file = new FileCtor(blobArr, fileName, opts);
        }
        if (file) built.push(file);
      } catch (fallbackErr) {
        const blob = new Blob(parts, { type: mime });
        built.push(new File([blob], fileName, {
          type: mime,
          lastModified: Date.now()
        }));
      }
    }
    return built;
  }

  function resolveTarget(xRatio, yRatio) {
    let doc = document;
    let win = window;
    let cx = Math.max(0, Math.min(win.innerWidth - 1, Math.round(xRatio * win.innerWidth)));
    let cy = Math.max(0, Math.min(win.innerHeight - 1, Math.round(yRatio * win.innerHeight)));
    let el = doc.elementFromPoint(cx, cy);

    // Drill into same-origin iframes up to 4 levels deep
    for (let depth = 0; depth < 4 && el && el.tagName === "IFRAME"; depth++) {
      try {
        const innerDoc = el.contentDocument;
        const innerWin = el.contentWindow;
        if (!innerDoc || !innerWin) break;
        const rect = el.getBoundingClientRect();
        cx = Math.max(0, Math.min(rect.width - 1, Math.round(cx - rect.left)));
        cy = Math.max(0, Math.min(rect.height - 1, Math.round(cy - rect.top)));
        doc = innerDoc;
        win = innerWin;
        el = doc.elementFromPoint(cx, cy);
      } catch (err) {
        break;
      }
    }

    if (!el) {
      el = doc.body || doc.documentElement;
    }
    return { doc, win, el, cx, cy };
  }

  function findNearbyFileInput(doc, startEl) {
    if (!startEl) return null;
    if (startEl.tagName === "INPUT" && startEl.type === "file" && !startEl.disabled) {
      return startEl;
    }
    const label = startEl.closest ? startEl.closest("label") : null;
    if (label) {
      if (label.control && label.control.tagName === "INPUT"
          && label.control.type === "file" && !label.control.disabled) {
        return label.control;
      }
      if (label.htmlFor) {
        const byId = doc.getElementById(label.htmlFor);
        if (byId && byId.tagName === "INPUT" && byId.type === "file" && !byId.disabled) {
          return byId;
        }
      }
      const inLabel = label.querySelector('input[type="file"]:not([disabled])');
      if (inLabel) return inLabel;
    }
    if (startEl.querySelector) {
      const inside = startEl.querySelector('input[type="file"]:not([disabled])');
      if (inside) return inside;
    }
    let cur = startEl.parentElement;
    for (let i = 0; i < 4 && cur; i++) {
      const inputs = cur.querySelectorAll('input[type="file"]:not([disabled])');
      if (inputs.length === 1) return inputs[0];
      cur = cur.parentElement;
    }
    const allInputs = doc.querySelectorAll('input[type="file"]:not([disabled])');
    if (allInputs.length === 1) return allInputs[0];
    return null;
  }

  function createPageDataTransfer(win, fileObjs, maxCount) {
    const pWin = pageWindow(win);
    const DtCtor = (pWin && pWin.DataTransfer) ? pWin.DataTransfer : DataTransfer;
    const dt = new DtCtor();
    const limit = typeof maxCount === "number" ? Math.min(maxCount, fileObjs.length) : fileObjs.length;
    for (let i = 0; i < limit; i++) {
      dt.items.add(fileObjs[i]);
    }
    try {
      dt.effectAllowed = "all";
      dt.dropEffect = "copy";
    } catch (ignored) {}
    return dt;
  }

  function createPageEventInit(win, dt, cx, cy) {
    const pWin = pageWindow(win);
    const ObjCtor = (pWin && pWin.Object) ? pWin.Object : Object;
    const init = new ObjCtor();
    init.bubbles = true;
    init.cancelable = true;
    init.composed = true;
    init.view = pWin;
    init.dataTransfer = dt;
    init.clientX = cx;
    init.clientY = cy;
    init.screenX = cx;
    init.screenY = cy;
    return init;
  }

  function assignFilesToInput(win, input, files) {
    try {
      const pWin = pageWindow(win);
      const useClone = canClone(win);
      const count = input.multiple ? files.length : Math.min(1, files.length);
      const dt = createPageDataTransfer(win, files, count);
      const rawInput = unwrapElement(input);
      rawInput.files = dt.files;

      const EvCtor = (pWin && pWin.Event) ? pWin.Event : Event;
      const evOpts = useClone ? cloneInto({ bubbles: true }, pWin) : { bubbles: true };
      dispatchInPage(input, new EvCtor("input", evOpts));
      dispatchInPage(input, new EvCtor("change", evOpts));
      return !!(rawInput.files && rawInput.files.length > 0);
    } catch (e) {
      return false;
    }
  }

  function performDrop(target, fileObjs) {
    const { doc, win, el, cx, cy } = target;
    if (!el) return false;

    const pWin = pageWindow(win);
    const DragEvCtor = (pWin && pWin.DragEvent) ? pWin.DragEvent : DragEvent;
    const dt = createPageDataTransfer(win, fileObjs);
    const eventInit = createPageEventInit(win, dt, cx, cy);

    const directInput = (el.tagName === "INPUT" && el.type === "file" && !el.disabled) ? el : null;

    try {
      dispatchInPage(el, new DragEvCtor("dragenter", eventInit));
    } catch (ignored) {}
    try {
      dispatchInPage(el, new DragEvCtor("dragover", eventInit));
    } catch (ignored) {}

    let dropHandled = false;
    try {
      const dropEvent = new DragEvCtor("drop", eventInit);
      dispatchInPage(el, dropEvent);
      dropHandled = !!dropEvent.defaultPrevented;
    } catch (ignored) {}

    let inputAssigned = false;
    if (directInput) {
      inputAssigned = assignFilesToInput(win, directInput, fileObjs);
    } else if (!dropHandled) {
      const nearby = findNearbyFileInput(doc, el);
      if (nearby) {
        inputAssigned = assignFilesToInput(win, nearby, fileObjs);
      }
    }

    return dropHandled || inputAssigned;
  }

  function handleMessage(msg) {
    if (!msg || !msg.type) return;

    if (msg.type === "abort") {
      if (msg.dropId) pendingDrops.delete(msg.dropId);
      return;
    }

    if (msg.type === "chunk") {
      let drop = pendingDrops.get(msg.dropId);
      if (!drop) {
        drop = { files: new Map(), totalBytes: 0 };
        pendingDrops.set(msg.dropId, drop);
      }
      if (msg.data) {
        const bytes = decodeBase64ToBytes(msg.data);
        drop.totalBytes += bytes.byteLength;
        if (drop.totalBytes > MAX_DROP_BYTES) {
          pendingDrops.delete(msg.dropId);
          return;
        }
        let parts = drop.files.get(msg.fileIndex);
        if (!parts) {
          parts = [];
          drop.files.set(msg.fileIndex, parts);
        }
        parts.push(bytes);
      }
      return;
    }

    if (msg.type === "commit") {
      const drop = pendingDrops.get(msg.dropId);
      pendingDrops.delete(msg.dropId);

      const metaList = Array.isArray(msg.files) ? msg.files : [];
      const xRatio = typeof msg.xRatio === "number" ? msg.xRatio : 0.5;
      const yRatio = typeof msg.yRatio === "number" ? msg.yRatio : 0.5;
      const target = resolveTarget(xRatio, yRatio);
      const builtFiles = buildFilesForWindow(target.win, metaList, drop ? drop.files : null);

      let handled = false;
      try {
        handled = builtFiles.length > 0 && performDrop(target, builtFiles);
      } catch (err) {
        handled = false;
      }

      if (port) {
        try {
          port.postMessage({
            type: "dropResult",
            dropId: msg.dropId,
            handled: handled,
            fileCount: builtFiles.length
          });
        } catch (ignored) {}
      }
    }
  }

  connect();
})();
