(function () {
  if (window.__spoonGeckoDropInit) return;
  window.__spoonGeckoDropInit = true;

  const PORT_NAME = "spoongeckoDrop";
  const pendingDrops = new Map();
  let port = null;
  let reconnectTimer = null;

  function connect() {
    if (port) return;
    try {
      port = browser.runtime.connectNative(PORT_NAME);
      port.onMessage.addListener(handleMessage);
      port.onDisconnect.addListener(() => {
        port = null;
        if (!reconnectTimer) {
          reconnectTimer = setTimeout(() => {
            reconnectTimer = null;
            connect();
          }, 400);
        }
      });
    } catch (e) {
      port = null;
    }
  }

  function decodeBase64ToBytes(b64) {
    const bin = atob(b64);
    const len = bin.length;
    const out = new Uint8Array(len);
    for (let i = 0; i < len; i++) {
      out[i] = bin.charCodeAt(i);
    }
    return out;
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

  function assignFilesToInput(input, files) {
    try {
      const dt = new DataTransfer();
      const count = input.multiple ? files.length : Math.min(1, files.length);
      for (let i = 0; i < count; i++) {
        dt.items.add(files[i]);
      }
      input.files = dt.files;
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
      return input.files && input.files.length > 0;
    } catch (e) {
      return false;
    }
  }

  function performDrop(xRatio, yRatio, fileObjs) {
    const dt = new DataTransfer();
    for (let i = 0; i < fileObjs.length; i++) {
      dt.items.add(fileObjs[i]);
    }

    const { doc, el, cx, cy } = resolveTarget(xRatio, yRatio);
    if (!el) return false;

    const directInput = (el.tagName === "INPUT" && el.type === "file" && !el.disabled) ? el : null;
    const eventInit = {
      bubbles: true,
      cancelable: true,
      composed: true,
      dataTransfer: dt,
      clientX: cx,
      clientY: cy
    };

    try {
      el.dispatchEvent(new DragEvent("dragenter", eventInit));
    } catch (ignored) {}
    try {
      el.dispatchEvent(new DragEvent("dragover", eventInit));
    } catch (ignored) {}

    let dropHandled = false;
    try {
      const dropEvent = new DragEvent("drop", eventInit);
      el.dispatchEvent(dropEvent);
      dropHandled = !!dropEvent.defaultPrevented;
    } catch (ignored) {}

    let inputAssigned = false;
    if (directInput) {
      inputAssigned = assignFilesToInput(directInput, fileObjs);
    } else if (!dropHandled) {
      const nearby = findNearbyFileInput(doc, el);
      if (nearby) {
        inputAssigned = assignFilesToInput(nearby, fileObjs);
      }
    }

    return dropHandled || inputAssigned;
  }

  function handleMessage(msg) {
    if (!msg || !msg.type) return;

    if (msg.type === "chunk") {
      let drop = pendingDrops.get(msg.dropId);
      if (!drop) {
        drop = new Map();
        pendingDrops.set(msg.dropId, drop);
      }
      let parts = drop.get(msg.fileIndex);
      if (!parts) {
        parts = [];
        drop.set(msg.fileIndex, parts);
      }
      if (msg.data) {
        parts.push(decodeBase64ToBytes(msg.data));
      }
      return;
    }

    if (msg.type === "commit") {
      const drop = pendingDrops.get(msg.dropId);
      pendingDrops.delete(msg.dropId);

      const metaList = Array.isArray(msg.files) ? msg.files : [];
      const builtFiles = [];
      for (let i = 0; i < metaList.length; i++) {
        const meta = metaList[i];
        const parts = drop ? (drop.get(i) || []) : [];
        const blob = new Blob(parts, { type: meta.mime || "application/octet-stream" });
        const file = new File([blob], meta.name || "file", {
          type: meta.mime || "application/octet-stream",
          lastModified: Date.now()
        });
        builtFiles.push(file);
      }

      let handled = false;
      try {
        handled = builtFiles.length > 0 && performDrop(
          typeof msg.xRatio === "number" ? msg.xRatio : 0.5,
          typeof msg.yRatio === "number" ? msg.yRatio : 0.5,
          builtFiles
        );
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
