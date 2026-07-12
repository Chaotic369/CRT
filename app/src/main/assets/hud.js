(function () {
  'use strict';

  if (window.__fhInjected) return;
  window.__fhInjected = true;

  var bridge = window.AndroidPersist;

  function loadPos() {
    try {
      var raw = bridge && bridge.getItem('fh-pos');
      return raw ? JSON.parse(raw) : null;
    } catch (e) {
      return null;
    }
  }

  function savePos(pos) {
    try {
      if (bridge) bridge.setItem('fh-pos', JSON.stringify(pos));
    } catch (e) {}
  }

  // ---------- host element, locked against page CSS ----------
  var host = document.createElement('div');
  host.id = 'fh-host';
  host.style.cssText =
    'all: initial !important;' +
    'position: fixed !important;' +
    'z-index: 2147483647 !important;' +
    'bottom: 24px !important;' +
    'right: 24px !important;' +
    'display: block !important;' +
    'touch-action: none !important;';

  var saved = loadPos();
  if (saved && saved.top && saved.left) {
    host.style.setProperty('bottom', 'auto', 'important');
    host.style.setProperty('right', 'auto', 'important');
    host.style.setProperty('top', saved.top, 'important');
    host.style.setProperty('left', saved.left, 'important');
  }

  document.documentElement.appendChild(host);

  var shadow = host.attachShadow({ mode: 'open' });

  var style = document.createElement('style');
  style.textContent = `
    * { box-sizing: border-box; }
    #fh-root {
      position: relative;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      user-select: none;
    }
    #fh-toggle {
      width: 46px;
      height: 46px;
      border-radius: 50%;
      background: #1f6feb;
      box-shadow: 0 3px 10px rgba(0,0,0,0.35);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: grab;
      border: none;
      touch-action: none;
      transition: background 0.15s ease;
    }
    #fh-toggle:active { cursor: grabbing; }
    #fh-toggle svg {
      width: 20px;
      height: 20px;
      fill: none;
      stroke: #fff;
      stroke-width: 2;
      stroke-linecap: round;
      stroke-linejoin: round;
      pointer-events: none;
    }
    #fh-panel {
      position: absolute;
      bottom: 56px;
      right: 0;
      display: none;
      align-items: center;
      gap: 6px;
      background: #1c1f26;
      padding: 8px;
      border-radius: 10px;
      box-shadow: 0 6px 20px rgba(0,0,0,0.4);
      border: 1px solid rgba(255,255,255,0.08);
    }
    #fh-panel.fh-open { display: flex; }
    #fh-input {
      width: 200px;
      padding: 9px 10px;
      border-radius: 6px;
      border: 1px solid rgba(255,255,255,0.15);
      background: #12141a;
      color: #f2f2f2;
      font-size: 13px;
      outline: none;
    }
    #fh-input:focus { border-color: #1f6feb; }
    #fh-go {
      padding: 9px 14px;
      border-radius: 6px;
      border: none;
      background: #1f6feb;
      color: #fff;
      font-size: 13px;
      font-weight: 600;
      cursor: pointer;
      white-space: nowrap;
    }
  `;
  shadow.appendChild(style);

  var wrapper = document.createElement('div');
  wrapper.id = 'fh-root';
  wrapper.innerHTML = `
    <div id="fh-panel">
      <input id="fh-input" type="text" placeholder="Enter a URL or search…" autocomplete="off" spellcheck="false" />
      <button id="fh-go">Go</button>
    </div>
    <button id="fh-toggle" title="Drag to move · tap to open">
      <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.5 3 2.5 15 0 18M12 3c-2.5 3-2.5 15 0 18"/></svg>
    </button>
  `;
  shadow.appendChild(wrapper);

  var toggleBtn = shadow.querySelector('#fh-toggle');
  var panel = shadow.querySelector('#fh-panel');
  var input = shadow.querySelector('#fh-input');
  var goBtn = shadow.querySelector('#fh-go');

  function openPanel() {
    panel.classList.add('fh-open');
    input.value = '';
    setTimeout(function () { input.focus(); }, 20);
  }
  function closePanel() {
    panel.classList.remove('fh-open');
  }
  function togglePanel() {
    panel.classList.contains('fh-open') ? closePanel() : openPanel();
  }

  function go() {
    var val = input.value.trim();
    if (!val) return;

    var looksLikeUrl = /^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(val) ||
                        /^[\w-]+(\.[\w-]+)+(:\d+)?(\/.*)?$/.test(val);

    if (looksLikeUrl) {
      if (!/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(val)) {
        val = 'https://' + val;
      }
      window.location.href = val;
    } else {
      window.location.href = 'https://www.google.com/search?q=' + encodeURIComponent(val);
    }
  }

  goBtn.addEventListener('click', go);
  input.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') go();
    if (e.key === 'Escape') closePanel();
  });

  document.addEventListener('pointerdown', function (e) {
    var path = e.composedPath();
    if (!path.includes(host)) closePanel();
  });

  var dragging = false;
  var dragged = false;
  var startX, startY, startTop, startLeft;

  function clamp(v, min, max) {
    return Math.min(Math.max(v, min), max);
  }

  toggleBtn.addEventListener('pointerdown', function (e) {
    dragging = true;
    dragged = false;
    toggleBtn.setPointerCapture(e.pointerId);
    var rect = host.getBoundingClientRect();
    startX = e.clientX;
    startY = e.clientY;
    startTop = rect.top;
    startLeft = rect.left;
    e.preventDefault();
  });

  toggleBtn.addEventListener('pointermove', function (e) {
    if (!dragging) return;
    var dx = e.clientX - startX;
    var dy = e.clientY - startY;
    if (Math.abs(dx) > 4 || Math.abs(dy) > 4) dragged = true;

    var w = host.offsetWidth || 46;
    var h = host.offsetHeight || 46;
    var newTop = clamp(startTop + dy, 0, window.innerHeight - h);
    var newLeft = clamp(startLeft + dx, 0, window.innerWidth - w);

    host.style.setProperty('bottom', 'auto', 'important');
    host.style.setProperty('right', 'auto', 'important');
    host.style.setProperty('top', newTop + 'px', 'important');
    host.style.setProperty('left', newLeft + 'px', 'important');
  });

  toggleBtn.addEventListener('pointerup', function (e) {
    if (dragging) {
      dragging = false;
      toggleBtn.releasePointerCapture(e.pointerId);
      savePos({ top: host.style.top, left: host.style.left });
    }
  });

  toggleBtn.addEventListener('click', function () {
    if (dragged) { dragged = false; return; }
    togglePanel();
  });
})();
