const MIN_PERCENT = 10;
const MAX_PERCENT = 90;
const rootEl = document.getElementById('split-root');

let nodeIdCounter = 0;
let rootNode = null;
let activeLeafNode = null;
let lastKnownUrl = '';
let bookmarks = [];

// API bridge to Java WebAppInterface
const api = window.AndroidAPI || {
    navigate: (url) => console.log('Navigate', url),
    zoomIn: () => console.log('Zoom in'),
    zoomOut: () => console.log('Zoom out'),
    toggleFullscreen: () => console.log('Fullscreen'),
    splitPane: (dir) => console.log('Split', dir),
    closePane: () => console.log('Close pane'),
    addBookmark: (label, url) => console.log('Add BM', label, url),
    deleteBookmark: (idx) => console.log('Delete BM', idx),
    getBookmarks: () => '[]',
    getDownloads: () => '[]'
};

function createLeafNode(url) {
  return { type: 'leaf', id: 'n' + (++nodeIdCounter), url, parent: null, el: null, webview: null };
}

function createSplitNode(direction, first, second) {
  const node = { type: 'split', id: 's' + (++nodeIdCounter), direction, ratio: 50, first, second, parent: null, el: null, firstWrap: null, secondWrap: null, resizerEl: null };
  first.parent = node; second.parent = node;
  return node;
}

function renderNode(node) {
  return node.type === 'leaf' ? renderLeaf(node) : renderSplit(node);
}

function renderLeaf(node) {
  const iframe = document.createElement('iframe');
  iframe.setAttribute('src', node.url);
  iframe.setAttribute('sandbox', 'allow-scripts allow-same-origin allow-popups');
  iframe.addEventListener('focus', () => setActiveLeaf(node));
  node.el = iframe;
  node.webview = iframe;
  return iframe;
}

function setActiveLeaf(node) {
  if (!node || node.type !== 'leaf' || !node.webview) return;
  if (activeLeafNode && activeLeafNode !== node && activeLeafNode.webview) {
    activeLeafNode.webview.classList.remove('pane-active');
  }
  activeLeafNode = node;
  node.webview.classList.add('pane-active');
}

function renderSplit(node) {
  const container = document.createElement('div');
  container.className = 'split-container ' + node.direction;

  const firstWrap = document.createElement('div');
  firstWrap.className = 'split-child';
  firstWrap.style.flex = node.ratio + ' 1 0';
  firstWrap.appendChild(renderNode(node.first));

  const resizer = document.createElement('div');
  resizer.className = 'split-resizer ' + (node.direction === 'row' ? 'vertical' : 'horizontal');
  
  let dragging = false;
  resizer.addEventListener('mousedown', (e) => { dragging = true; resizer.classList.add('active'); e.preventDefault(); });
  window.addEventListener('mousemove', (e) => {
    if (!dragging) return;
    const rect = node.el.getBoundingClientRect();
    let percent = node.direction === 'row' ? ((e.clientX - rect.left) / rect.width) * 100 : ((e.clientY - rect.top) / rect.height) * 100;
    percent = Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percent));
    node.ratio = percent;
    node.firstWrap.style.flex = percent + ' 1 0';
    node.secondWrap.style.flex = (100 - percent) + ' 1 0';
  });
  window.addEventListener('mouseup', () => { dragging = false; resizer.classList.remove('active'); });

  const secondWrap = document.createElement('div');
  secondWrap.className = 'split-child';
  secondWrap.style.flex = (100 - node.ratio) + ' 1 0';
  secondWrap.appendChild(renderNode(node.second));

  container.appendChild(firstWrap);
  container.appendChild(resizer);
  container.appendChild(secondWrap);
  node.el = container; node.firstWrap = firstWrap; node.secondWrap = secondWrap; node.resizerEl = resizer;
  return container;
}

// Event Listeners for Bottom Bar Controls
document.getElementById('btn-zoom-in').addEventListener('click', () => api.zoomIn());
document.getElementById('btn-zoom-out').addEventListener('click', () => api.zoomOut());
document.getElementById('btn-fullscreen').addEventListener('click', () => api.toggleFullscreen());
document.getElementById('btn-split-row').addEventListener('click', () => api.splitPane('row'));
document.getElementById('btn-split-col').addEventListener('click', () => api.splitPane('column'));
document.getElementById('btn-close-pane').addEventListener('click', () => api.closePane());

const addressInput = document.getElementById('address-input');
addressInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    if (addressInput.value.trim()) api.navigate(addressInput.value);
    addressInput.blur();
  }
});

// Initialization 
function initRoot(url) {
  rootNode = createLeafNode(url);
  rootEl.appendChild(renderNode(rootNode));
  setActiveLeaf(rootNode);
}
initRoot("https://google.com");