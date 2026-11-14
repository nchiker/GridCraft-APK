// The bridge between the Android app (window.Android, MainActivity.Bridge) and GridCraft's
// page host mode (events on the canvas) plus its command API (window.gridcraft).
// GridCraft itself is not changed: everything here goes through what HOSTING.md documents.
"use strict";
(function () {
  const A = window.Android;
  const canvas = document.getElementById("gridcraft_canvas");

  /** Sheet zoom the app shows (the owner's choice); each sheet's own zoom is put back on save. */
  const ZOOM = 200;
  /** Interface (menus, dialogs) size. */
  const INTERFACE = 85;
  /** Undo entries that don't change what is in the file, for the "unsaved changes" check. */
  const VIEW_ONLY = new Set(["Zoom", "Activate Sheet"]);

  let fileZoom = {};       // sheet index -> zoom stored in the file when it was opened
  let baseline = "[]";     // content-changing undo entries at the last open/save
  let saveWaiter = null;   // resolves with the next gridcraft:save
  let busy = Promise.resolve();

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const log = (m) => { try { A.log(String(m)); } catch (_) {} };

  async function api() {
    for (let i = 0; !window.gridcraft; i++) {
      if (i > 1200) throw new Error("GridCraft did not start");
      await sleep(25);
    }
    return window.gridcraft;
  }

  function b64ToBytes(b64) {
    const bin = atob(b64);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  function bytesToB64(buf) {
    const u = new Uint8Array(buf);
    let s = "";
    for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000));
    return btoa(s);
  }

  async function edits(g) {
    const d = await g.docInspect();
    return JSON.stringify((d.undo || []).filter((u) => !VIEW_ONLY.has(u)));
  }

  /** Runs `f` after earlier queued work, so open/save/zoom never interleave. */
  function queue(f) {
    const next = busy.then(f, f);
    busy = next.catch(() => {});
    return next;
  }

  async function setZooms(g, zoomFor) {
    const d = await g.docInspect();
    const active = d.activeSheet;
    let moved = false;
    for (let i = 0; i < d.sheets.length; i++) {
      const s = d.sheets[i];
      const want = zoomFor(i, s);
      if (want == null || s.visibility !== "Visible" || s.zoom === want) continue;
      await g.execute("sheet.activate", { sheet: i });
      await g.execute("view.zoom", { percent: want });
      moved = true;
    }
    if (moved) await g.execute("sheet.activate", { sheet: active });
  }

  async function showAtAppZoom(g) {
    const d = await g.docInspect();
    fileZoom = {};
    d.sheets.forEach((s, i) => { fileZoom[i] = s.zoom; });
    await setZooms(g, () => ZOOM);
    baseline = await edits(g);
  }

  async function setUp(g) {
    await g.execute("view.interfaceScale", { percent: INTERFACE });
    const ui = await g.uiInspect();
    if (!ui.ui.ribbonCollapsed) await g.execute("view.collapseRibbon", {});
    await showAtAppZoom(g);
  }

  /** Resolves once the page is on screen (GridCraft only answers commands while it draws). */
  function visible() {
    if (document.visibilityState === "visible") return Promise.resolve();
    return new Promise((r) => document.addEventListener("visibilitychange", function on() {
      if (document.visibilityState !== "visible") return;
      document.removeEventListener("visibilitychange", on);
      r();
    }));
  }

  canvas.addEventListener("gridcraft:ready", () => {
    queue(async () => {
      const g = await api();
      // Started with the screen off (or the app in the background): set up when it's shown.
      for (let attempt = 1; ; attempt++) {
        await visible();
        try {
          await setUp(g);
          break;
        } catch (e) {
          log("set-up attempt " + attempt + " failed: " + (e && e.message || e));
          if (attempt >= 5) throw e;
          await sleep(500);
        }
      }
      A.onReady();
    }).catch((e) => GC.failed(String(e && e.message || e)));
  });

  canvas.addEventListener("gridcraft:save", (ev) => {
    const w = saveWaiter;
    saveWaiter = null;
    if (w) w(ev.detail);
    else log("unexpected gridcraft:save " + (ev.detail && ev.detail.name));
  });

  canvas.addEventListener("gridcraft:failed", (ev) => GC.failed(String(ev.detail && ev.detail.error)));

  function reply(id, ok, value) {
    A.onResult(id, JSON.stringify(ok ? { ok: true, value: value === undefined ? null : value } : { ok: false, error: String(value) }));
  }

  const GC = {
    failed(msg) { try { A.onFailed(msg); } catch (_) {} },

    /** Runs one GridCraft command; the answer goes to Android.onResult(id, json). */
    run(id, command, paramsJson) {
      queue(async () => {
        const g = await api();
        return g.execute(command, JSON.parse(paramsJson || "{}"));
      }).then((v) => reply(id, true, v), (e) => reply(id, false, String(e && e.message || e).replace(/^Error: /, "")));
    },

    /** Opens the file the app is holding (Android.pendingName/pendingData). */
    openPending(id) {
      queue(async () => {
        const g = await api();
        const name = A.pendingName();
        const bytes = b64ToBytes(A.pendingData());
        const before = await g.uiInspect();
        const beforeDocs = JSON.stringify(before.documents);
        canvas.dispatchEvent(new CustomEvent("gridcraft:open", { detail: { name, bytes } }));
        // Wait for GridCraft to finish reading it: the open document changes, or it shows a message.
        for (let i = 0; ; i++) {
          await sleep(50);
          const ui = await g.uiInspect();
          if (JSON.stringify(ui.documents) !== beforeDocs) break;
          if (ui.message) throw new Error(typeof ui.message === "string" ? ui.message : JSON.stringify(ui.message));
          if (i > 2400) throw new Error("Opening took too long");
        }
        await closeOthers(g);
        await showAtAppZoom(g);
        const d = await g.docInspect();
        return { title: d.title, sheets: d.sheets.length };
      }).then((v) => reply(id, true, v), (e) => reply(id, false, e && e.message || e));
    },

    /** Close: replaces the open workbook with a new blank one (the app asked about saving first). */
    closeToBlank(id) {
      queue(async () => {
        const g = await api();
        await g.execute("file.new", {});
        await closeOthers(g);
        await showAtAppZoom(g);
        return { title: (await g.docInspect()).title };
      }).then((v) => reply(id, true, v), (e) => reply(id, false, e && e.message || e));
    },

    /** Saves: puts each sheet's own zoom back, asks GridCraft for the workbook, shows 200% again. */
    save(id) {
      queue(async () => {
        const g = await api();
        await setZooms(g, (i) => fileZoom[i]);
        const got = new Promise((resolve, reject) => {
          saveWaiter = resolve;
          setTimeout(() => reject(new Error("GridCraft did not hand over the file")), 120000);
        });
        await g.execute("file.save", {});
        const detail = await got;
        await setZooms(g, (i) => (i in fileZoom ? ZOOM : null));
        A.onSaveBytes(id, String(detail.name || ""), bytesToB64(detail.bytes));
        return null;
      }).catch((e) => reply(id, false, e && e.message || e));
    },

    /** The app wrote the file: what is open now counts as saved. */
    markSaved(id) {
      queue(async () => { baseline = await edits(await api()); }).then(() => reply(id, true, null), (e) => reply(id, false, e));
    },

    /**
     * Undo/Redo for the bar buttons: one real edit per tap, taking any zoom or sheet switches next
     * to it along, so the app's own zoom changes (on open and save) are never undone on their own.
     * Answers false when there is no edit to undo/redo.
     */
    undo(id) { this.history(id, "undo"); },
    redo(id) { this.history(id, "redo"); },
    history(id, which) {
      queue(async () => {
        const g = await api();
        const list = (await g.execute("history.list", {}))[which] || []; // next step first
        const real = list.findIndex((u) => !VIEW_ONLY.has(u));
        if (real < 0) return false;
        let steps = real + 1;
        // Redo also replays the view changes that followed the edit, as undo took them back with it.
        if (which === "redo") while (steps < list.length && VIEW_ONLY.has(list[steps])) steps++;
        await g.execute("edit." + which, { steps });
        return true;
      }).then((v) => reply(id, true, v), (e) => reply(id, false, e && e.message || e));
    },

    /** Back: closes a dialog, a cell edit or the open ribbon; answers false when there was none. */
    back(id) {
      queue(async () => {
        const g = await api();
        const ui = await g.uiInspect();
        if (ui.dialog) { await g.request("ui.dialog.cancel", {}); return true; }
        if (ui.editor) { await g.request("ui.edit.cancel", {}); return true; }
        if (!ui.ui.ribbonCollapsed) { await g.execute("view.collapseRibbon", {}); return true; }
        return false;
      }).then((v) => reply(id, true, v), (e) => reply(id, false, e));
    },

    /** Whether there are changes that aren't saved (zoom and sheet switching don't count). */
    isDirty(id) {
      queue(async () => (await edits(await api())) !== baseline).then((v) => reply(id, true, v), (e) => reply(id, false, e));
    },
  };

  /** Host mode can keep the previous workbook open in another window; the app shows one file. */
  async function closeOthers(g) {
    const ui = await g.uiInspect();
    for (const w of ui.windows || []) {
      if (w.active) continue;
      await g.request("engine.execute", { command: "file.close", params: { document: true, force: true }, doc: w.doc });
    }
  }

  window.GC = GC;
  window.addEventListener("error", (e) => log("page error: " + e.message));
  window.addEventListener("unhandledrejection", (e) => log("page rejection: " + (e.reason && e.reason.message || e.reason)));
})();
