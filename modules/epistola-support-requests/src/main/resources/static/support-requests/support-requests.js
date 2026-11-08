// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Feedback feature behavior (CSP-safe: no inline scripts — ADR 0010).
 *
 * Included from the feedback templates as <script type="module" src=…>. The
 * fragments that include it are re-swapped by HTMX (the footer on every
 * boosted navigation), but a module URL is only evaluated once per document;
 * the __epSupportRequestInit guard makes that idempotency explicit. All wiring is
 * driven by data-* hooks so it works for the initial page load and for every
 * HTMX-swapped fragment:
 *
 * - [data-support-request-fab][data-tenant-id]        → (re)mounts the feedback FAB
 * - form[data-support-request-form][data-app-version] → wires the submit form
 *   (auto-captured fields + screenshot capture)
 * - dialog[data-backdrop-close]                → clicking the backdrop closes
 *   the dialog
 */
import { initSupportRequestFab } from './support-request-fab.js';

/** All elements in root (root itself included) matching selector. */
function matchAll(root, selector) {
  const found = [];
  if (root instanceof Element && root.matches(selector)) found.push(root);
  if (typeof root.querySelectorAll === 'function') found.push(...root.querySelectorAll(selector));
  return found;
}

/** (Re)mount the feedback FAB for each fresh marker element. */
function initFabs(root) {
  for (const marker of matchAll(root, '[data-support-request-fab]')) {
    if (marker.dataset.supportRequestFabWired) continue;
    marker.dataset.supportRequestFabWired = 'true';
    initSupportRequestFab(marker.dataset.tenantId);
  }
}

/** Wire each freshly rendered submit form. */
function initSubmitForms(root) {
  for (const form of matchAll(root, 'form[data-support-request-form]')) {
    if (form.dataset.supportRequestFormWired) continue;
    form.dataset.supportRequestFormWired = 'true';
    initSubmitForm(form);
  }
}

function initSubmitForm(form) {
  const appVersion = form.dataset.appVersion || 'dev';

  // Auto-capture URL
  const sourceUrlField = form.querySelector('#fb-sourceUrl');
  if (sourceUrlField) {
    sourceUrlField.value = window.location.href;
  }

  // Auto-capture console logs
  const consoleLogsField = form.querySelector('#fb-consoleLogs');
  if (consoleLogsField && window.__epistola_console_buffer) {
    consoleLogsField.value = JSON.stringify(window.__epistola_console_buffer.slice(-100));
  }

  // The trace ids of the last requests on this page (support-request-capture.js), for the server log
  const traceIdsField = form.querySelector('#fb-traceIds');
  if (traceIdsField && window.__epistola_trace_buffer) {
    traceIdsField.value = JSON.stringify(window.__epistola_trace_buffer.slice(-20));
  }

  // Auto-capture metadata
  const metadataField = form.querySelector('#fb-metadata');
  if (metadataField) {
    metadataField.value = JSON.stringify({
      appVersion: appVersion,
      userAgent: navigator.userAgent,
      language: navigator.language,
      platform: navigator.platform,
      viewport: {
        width: window.innerWidth,
        height: window.innerHeight,
      },
      screen: {
        width: screen.width,
        height: screen.height,
        pixelRatio: window.devicePixelRatio,
      },
      url: window.location.href,
      timestamp: new Date().toISOString(),
    });
  }

  // Screenshot handling
  const screenshotData = form.querySelector('#fb-screenshotData');
  const screenshotPreview = form.querySelector('#fb-screenshot-preview');
  const screenshotImg = form.querySelector('#fb-screenshot-img');
  const screenshotActions = form.querySelector('#fb-screenshot-actions');
  const screenshotRemove = form.querySelector('#fb-screenshot-remove');
  const screenshotError = form.querySelector('#fb-screenshot-error');

  function setScreenshot(dataUrl) {
    screenshotError.style.display = 'none';
    screenshotData.value = dataUrl;
    screenshotImg.src = dataUrl;
    screenshotPreview.style.display = 'block';
    screenshotActions.style.display = 'none';
  }

  function showScreenshotError(message) {
    screenshotError.textContent = message;
    screenshotError.style.display = 'block';
  }

  function clearScreenshot(e) {
    if (e) {
      e.stopPropagation();
      e.preventDefault();
    }
    screenshotData.value = '';
    screenshotImg.src = '';
    screenshotPreview.style.display = 'none';
    screenshotActions.style.display = 'flex';
  }

  // Remove button
  screenshotRemove.addEventListener('click', clearScreenshot);

  // Capture buttons — dialog lifecycle managed here, screenshot module is decoupled.
  // The submit form is hosted by whichever dialog opened it: the FAB dialog
  // (#support-request-fab-dialog) or the feedback list page dialog (#support-request-dialog).
  // Resolve the enclosing <dialog> rather than hardcoding one id, so the overlay
  // is hidden during capture regardless of where the form was opened from.
  const dialog = screenshotActions.closest('dialog');
  const fab = document.getElementById('support-request-fab');
  const popover = document.getElementById('support-request-popover');

  function onCaptureStart() {
    screenshotError.style.display = 'none';
    if (dialog) dialog.close();
    if (fab) fab.style.display = 'none';
    if (popover) popover.classList.remove('support-request-popover--open');
  }

  function onCaptureEnd() {
    if (fab) fab.style.display = '';
    if (dialog) dialog.showModal();
  }

  import('./support-request-screenshot.js').then(
    ({ captureRegion, captureViewport, isSupported }) => {
      if (!isSupported) {
        screenshotActions.style.display = 'none';
        form.querySelector('#fb-screenshot-unsupported').style.display = 'block';
        return;
      }

      const captureOpts = () => ({
        onCapture: setScreenshot,
        onStart: onCaptureStart,
        onEnd: onCaptureEnd,
        onError: showScreenshotError,
      });

      form.querySelector('#fb-capture-region')?.addEventListener('click', (e) => {
        e.preventDefault();
        captureRegion(captureOpts());
      });
      form.querySelector('#fb-capture-viewport')?.addEventListener('click', (e) => {
        e.preventDefault();
        captureViewport(captureOpts());
      });
    },
  );
}

if (!window.__epSupportRequestInit) {
  window.__epSupportRequestInit = true;

  // htmx:load fires for the initial page and for every swapped-in element,
  // so fresh markers/forms get wired no matter how they arrive.
  document.addEventListener('htmx:load', (e) => {
    const root = e.detail?.elt;
    if (root) {
      initFabs(root);
      initSubmitForms(root);
    }
  });

  // Close a dialog when its backdrop is clicked (the click target is the
  // <dialog> element itself only for backdrop clicks).
  document.addEventListener('click', (e) => {
    const dialog =
      e.target instanceof Element ? e.target.closest('dialog[data-backdrop-close]') : null;
    if (dialog && e.target === dialog) dialog.close();
  });

  // Module fetches are async: the swap that inserted this script tag may have
  // settled (htmx:load already fired) before evaluation, so also initialize
  // whatever is in the document right now. The *-wired guards keep this and
  // the htmx:load path from double-initializing the same element.
  initFabs(document);
  initSubmitForms(document);
}
