/**
 * Screenshot capture module for feedback submissions.
 *
 * Provides two capture modes:
 * - Region selection: user draws a rectangle on the page
 * - Full page: captures the entire scrollable page
 *
 * Uses html-to-image (lazy-loaded from CDN) for DOM-to-image conversion.
 */

async function getHtmlToImage() {
  if (!window.__htmlToImage) {
    window.__htmlToImage = await import('https://esm.sh/html-to-image@1.11.13');
  }
  return window.__htmlToImage;
}

function injectStyles() {
  if (document.getElementById('fb-capture-styles')) return;
  const style = document.createElement('style');
  style.id = 'fb-capture-styles';
  style.textContent = `
.fb-capture-overlay {
    position: fixed;
    inset: 0;
    z-index: 10000;
    cursor: crosshair;
    background: rgba(0, 0, 0, 0.15);
}
.fb-capture-hint {
    position: fixed;
    top: 1rem;
    left: 50%;
    transform: translateX(-50%);
    background: var(--ep-foreground, #0f172a);
    color: var(--ep-background, #fff);
    padding: 0.5rem 1rem;
    border-radius: var(--ep-radius, 0.375rem);
    font-size: var(--ep-text-sm, 0.875rem);
    z-index: 10001;
    pointer-events: none;
    user-select: none;
    white-space: nowrap;
}
.fb-capture-selection {
    position: fixed;
    border: 2px solid var(--ep-primary, #3b82f6);
    background: rgba(59, 130, 246, 0.08);
    pointer-events: none;
    z-index: 10001;
}
`;
  document.head.appendChild(style);
}

function hideEpistolaChromeForCapture() {
  const fab = document.getElementById('feedback-fab');
  const popover = document.getElementById('feedback-popover');
  if (fab) fab.style.display = 'none';
  if (popover) popover.classList.remove('feedback-popover--open');
}

function showEpistolaChromeAfterCapture() {
  const fab = document.getElementById('feedback-fab');
  if (fab) fab.style.display = '';
}

function getDialog() {
  return document.getElementById('feedback-fab-dialog');
}

/**
 * Capture a user-selected region of the page.
 * Closes the dialog, shows a selection overlay, captures the region, then reopens the dialog.
 *
 * @param {(dataUrl: string) => void} onCapture - called with the PNG data URL of the captured region
 */
export function captureRegion(onCapture) {
  injectStyles();

  const dialog = getDialog();
  if (dialog) dialog.close();
  hideEpistolaChromeForCapture();

  requestAnimationFrame(() => {
    const overlay = document.createElement('div');
    overlay.className = 'fb-capture-overlay';

    const hint = document.createElement('div');
    hint.className = 'fb-capture-hint';
    hint.textContent = 'Click and drag to select a region. Press Escape to cancel.';

    const selection = document.createElement('div');
    selection.className = 'fb-capture-selection';
    selection.style.display = 'none';

    overlay.appendChild(hint);
    overlay.appendChild(selection);
    document.body.appendChild(overlay);

    let startX = 0;
    let startY = 0;
    let dragging = false;

    function cleanup() {
      overlay.remove();
      showEpistolaChromeAfterCapture();
      if (dialog) dialog.showModal();
    }

    function onKeyDown(e) {
      if (e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation();
        document.removeEventListener('keydown', onKeyDown, true);
        cleanup();
      }
    }
    document.addEventListener('keydown', onKeyDown, true);

    overlay.addEventListener('mousedown', (e) => {
      e.preventDefault();
      startX = e.clientX;
      startY = e.clientY;
      dragging = true;
      selection.style.left = startX + 'px';
      selection.style.top = startY + 'px';
      selection.style.width = '0px';
      selection.style.height = '0px';
      selection.style.display = 'block';
    });

    overlay.addEventListener('mousemove', (e) => {
      if (!dragging) return;
      const x = Math.min(e.clientX, startX);
      const y = Math.min(e.clientY, startY);
      const w = Math.abs(e.clientX - startX);
      const h = Math.abs(e.clientY - startY);
      selection.style.left = x + 'px';
      selection.style.top = y + 'px';
      selection.style.width = w + 'px';
      selection.style.height = h + 'px';
    });

    overlay.addEventListener('mouseup', async (e) => {
      if (!dragging) return;
      dragging = false;
      document.removeEventListener('keydown', onKeyDown, true);

      const x = Math.min(e.clientX, startX);
      const y = Math.min(e.clientY, startY);
      const w = Math.abs(e.clientX - startX);
      const h = Math.abs(e.clientY - startY);

      // Minimum selection size
      if (w < 10 || h < 10) {
        cleanup();
        return;
      }

      overlay.remove();

      try {
        hint.remove();
        const { toCanvas } = await getHtmlToImage();

        // Capture the visible viewport as a canvas
        const fullCanvas = await toCanvas(document.documentElement, {
          width: window.innerWidth,
          height: window.innerHeight,
          canvasWidth: window.innerWidth,
          canvasHeight: window.innerHeight,
          pixelRatio: window.devicePixelRatio,
        });

        // Crop to the selected region
        const dpr = window.devicePixelRatio;
        const cropCanvas = document.createElement('canvas');
        cropCanvas.width = w * dpr;
        cropCanvas.height = h * dpr;
        const ctx = cropCanvas.getContext('2d');
        ctx.drawImage(fullCanvas, x * dpr, y * dpr, w * dpr, h * dpr, 0, 0, w * dpr, h * dpr);

        const dataUrl = cropCanvas.toDataURL('image/png');
        showEpistolaChromeAfterCapture();
        if (dialog) dialog.showModal();
        onCapture(dataUrl);
      } catch (err) {
        console.error('Screenshot capture failed:', err);
        showEpistolaChromeAfterCapture();
        if (dialog) dialog.showModal();
      }
    });
  });
}

/**
 * Capture the full scrollable page.
 *
 * @param {(dataUrl: string) => void} onCapture - called with the JPEG data URL of the captured page
 */
export async function captureFullPage(onCapture) {
  const dialog = getDialog();
  if (dialog) dialog.close();
  hideEpistolaChromeForCapture();

  // Allow dialog/FAB to be hidden before capturing
  await new Promise((resolve) => requestAnimationFrame(resolve));

  try {
    const { toCanvas } = await getHtmlToImage();

    const scrollHeight = document.documentElement.scrollHeight;
    const scrollWidth = document.documentElement.scrollWidth;

    const canvas = await toCanvas(document.documentElement, {
      width: scrollWidth,
      height: scrollHeight,
      canvasWidth: scrollWidth,
      canvasHeight: scrollHeight,
      pixelRatio: window.devicePixelRatio,
    });

    const dataUrl = canvas.toDataURL('image/jpeg', 0.8);
    showEpistolaChromeAfterCapture();
    if (dialog) dialog.showModal();
    onCapture(dataUrl);
  } catch (err) {
    console.error('Full page capture failed:', err);
    showEpistolaChromeAfterCapture();
    if (dialog) dialog.showModal();
  }
}
