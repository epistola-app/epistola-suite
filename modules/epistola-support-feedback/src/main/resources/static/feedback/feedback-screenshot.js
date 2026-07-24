// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Screenshot capture module for feedback submissions.
 *
 * Provides two capture modes:
 * - Region selection: user draws a rectangle on the page, captures that region
 * - Viewport: captures the entire visible viewport
 *
 * Uses the Screen Capture API (getDisplayMedia) — zero dependencies,
 * captures actual rendered pixels from the browser tab.
 *
 * This module is intentionally decoupled from dialog/FAB management.
 * Callers provide onStart/onEnd callbacks to handle their own UI lifecycle.
 */

/** Whether the Screen Capture API is available in this browser. */
export const isSupported = typeof navigator.mediaDevices?.getDisplayMedia === 'function';

/**
 * Hard cap on the encoded screenshot size. Keeps the urlencoded form POST under Tomcat's
 * form-post limit and the feedback submit gRPC message under its size limit. The hub does
 * not yet store assets out-of-band (see epistola-hub#8); until it does, attachments must
 * stay small.
 */
const MAX_SCREENSHOT_BYTES = 1024 * 1024; // 1 MB

function blobToDataUrl(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = reject;
    reader.readAsDataURL(blob);
  });
}

/**
 * Encode a canvas as a JPEG data URL no larger than [MAX_SCREENSHOT_BYTES], downscaling
 * progressively when needed. JPEG (not PNG) because screenshots are huge as PNG; quality
 * 0.85 is visually fine for feedback. Returns the data URL, or null if it can't fit even
 * when scaled down to the floor size.
 */
async function encodeUnderLimit(canvas) {
  let work = canvas;
  for (let attempt = 0; attempt < 8; attempt++) {
    const blob = await new Promise((resolve) => work.toBlob(resolve, 'image/jpeg', 0.85));
    if (blob && blob.size <= MAX_SCREENSHOT_BYTES) {
      return blobToDataUrl(blob);
    }
    if (work.width < 240 || work.height < 240) return null;
    const next = document.createElement('canvas');
    next.width = Math.max(1, Math.round(work.width * 0.8));
    next.height = Math.max(1, Math.round(work.height * 0.8));
    next.getContext('2d').drawImage(work, 0, 0, next.width, next.height);
    work = next;
  }
  return null;
}

/**
 * Capture a single frame from the current browser tab via getDisplayMedia.
 * Returns a canvas with the captured frame. Caller must crop as needed.
 * The media stream is always stopped, even on error.
 */
async function captureTabFrame() {
  const stream = await navigator.mediaDevices.getDisplayMedia({
    video: { displaySurface: 'browser' },
    preferCurrentTab: true,
  });

  try {
    const video = document.createElement('video');
    video.srcObject = stream;
    video.muted = true;

    await new Promise((resolve) => {
      video.onloadedmetadata = () => {
        video.play();
        resolve();
      };
    });

    // Wait one frame so the video has actual pixel data
    await new Promise((resolve) => requestAnimationFrame(resolve));

    const canvas = document.createElement('canvas');
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    const ctx = canvas.getContext('2d');
    ctx.drawImage(video, 0, 0);
    video.srcObject = null;

    return canvas;
  } finally {
    stream.getTracks().forEach((t) => t.stop());
  }
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

/**
 * Capture a user-selected region of the page.
 * Shows a selection overlay, lets the user draw a rectangle, then captures that region.
 *
 * @param {{ onCapture: (dataUrl: string) => void, onStart: () => void, onEnd: () => void, onError?: (msg: string) => void }} opts
 */
export function captureRegion({ onCapture, onStart, onEnd, onError = () => {} }) {
  injectStyles();
  onStart();

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
      onEnd();
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

      // Minimum selection size — treat tiny selections as cancellation
      if (w < 10 || h < 10) {
        cleanup();
        return;
      }

      overlay.remove();

      try {
        const fullCanvas = await captureTabFrame();

        // Map viewport coords to capture coords (capture may differ due to DPR/scaling)
        const scaleX = fullCanvas.width / window.innerWidth;
        const scaleY = fullCanvas.height / window.innerHeight;

        const cropCanvas = document.createElement('canvas');
        cropCanvas.width = Math.round(w * scaleX);
        cropCanvas.height = Math.round(h * scaleY);
        const ctx = cropCanvas.getContext('2d');
        ctx.drawImage(
          fullCanvas,
          Math.round(x * scaleX),
          Math.round(y * scaleY),
          cropCanvas.width,
          cropCanvas.height,
          0,
          0,
          cropCanvas.width,
          cropCanvas.height,
        );

        const dataUrl = await encodeUnderLimit(cropCanvas);
        if (dataUrl) {
          onCapture(dataUrl);
        } else {
          onError('Screenshot is too large to attach (max 1 MB). Try capturing a smaller region.');
        }
      } catch (err) {
        if (err.name !== 'NotAllowedError') {
          console.error('Screenshot capture failed:', err);
        }
      } finally {
        onEnd();
      }
    });
  });
}

/**
 * Capture the visible viewport.
 *
 * @param {{ onCapture: (dataUrl: string) => void, onStart: () => void, onEnd: () => void, onError?: (msg: string) => void }} opts
 */
export async function captureViewport({ onCapture, onStart, onEnd, onError = () => {} }) {
  onStart();

  // Allow caller's onStart UI changes to render
  await new Promise((resolve) => requestAnimationFrame(resolve));

  try {
    const canvas = await captureTabFrame();
    const dataUrl = await encodeUnderLimit(canvas);
    if (dataUrl) {
      onCapture(dataUrl);
    } else {
      onError('Screenshot is too large to attach (max 1 MB). Try capturing a smaller region.');
    }
  } catch (err) {
    if (err.name !== 'NotAllowedError') {
      console.error('Viewport capture failed:', err);
    }
  } finally {
    onEnd();
  }
}
