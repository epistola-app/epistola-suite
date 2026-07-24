// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// Global UI bootstrap: CSRF wiring, confirm dialogs, global HTMX error handling.
// Loaded once from fragments/htmx.html. Executable inline scripts are banned in
// templates (ADR 0010); behaviour belongs here or in behaviors.js.
// Global helper to get CSRF token from cookie
// Spring Security uses CookieCsrfTokenRepository with XSRF-TOKEN cookie
window.getCsrfToken = function () {
  var match = document.cookie.match(/XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : '';
};

// Configure HTMX to include CSRF token in all requests
document.addEventListener('htmx:configRequest', function (event) {
  var token = window.getCsrfToken();
  if (token) {
    event.detail.headers['X-XSRF-TOKEN'] = token;
  }
});

// ── Confirm dialog ──────────────────────────────────────────────

/**
 * Opens a confirm dialog for destructive actions.
 * Reads data-confirm-* attributes from the trigger element:
 *   data-confirm-title   - Dialog title
 *   data-confirm-message - Dialog body text
 *   data-confirm-url     - POST URL for the action
 *   data-confirm-target  - CSS selector for HTMX target to swap on success
 */
window.openConfirmDialog = function (trigger) {
  var dialog = document.getElementById('confirm-dialog');
  var titleEl = document.getElementById('confirm-dialog-title');
  var messageEl = document.getElementById('confirm-dialog-message');
  var errorEl = document.getElementById('confirm-dialog-error');
  var footerEl = document.getElementById('confirm-dialog-footer');

  titleEl.textContent = trigger.getAttribute('data-confirm-title') || 'Confirm';
  messageEl.textContent = trigger.getAttribute('data-confirm-message') || 'Are you sure?';
  errorEl.style.display = 'none';
  errorEl.textContent = '';

  // Build a fresh form with HTMX attributes
  var form = document.createElement('form');
  form.setAttribute('hx-post', trigger.getAttribute('data-confirm-url'));
  form.setAttribute('hx-target', trigger.getAttribute('data-confirm-target'));
  form.setAttribute('hx-swap', trigger.getAttribute('data-confirm-swap') || 'innerHTML');

  var submitBtn = document.createElement('button');
  submitBtn.type = 'submit';
  submitBtn.className = 'ep-btn ep-btn-destructive';
  submitBtn.textContent = 'Delete';
  submitBtn.setAttribute('data-testid', 'confirm-dialog-confirm');
  form.appendChild(submitBtn);

  footerEl.innerHTML = '';
  footerEl.appendChild(form);

  // Let HTMX process the dynamically created form
  htmx.process(form);

  // Handle success: close dialog
  form.addEventListener('htmx:afterRequest', function (event) {
    if (event.detail.successful) {
      closeConfirmDialog();
    }
  });

  // Handle error: show error message in dialog
  form.addEventListener('htmx:responseError', function (event) {
    try {
      var body = JSON.parse(event.detail.xhr.responseText);
      errorEl.textContent = body.detail || body.error || 'An error occurred.';
    } catch (e) {
      errorEl.textContent = 'An error occurred.';
    }
    errorEl.style.display = 'block';

    // Only a client error (4xx) is non-recoverable: the request was
    // rejected on its merits (e.g. deleting a resource other
    // resources depend on → 422), so re-submitting the same request
    // would only re-fail. Replace the destructive submit with a
    // Cancel button — the only sensible remaining action is to
    // dismiss the dialog. A 5xx is transient; leave Delete in place
    // so the user can retry without re-opening the dialog.
    var status = event.detail.xhr.status;
    if (status >= 400 && status < 500) {
      submitBtn.remove();
      if (!footerEl.querySelector('[data-testid="confirm-dialog-cancel"]')) {
        var cancelBtn = document.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.className = 'ep-btn ep-btn-ghost';
        cancelBtn.textContent = 'Cancel';
        cancelBtn.setAttribute('data-testid', 'confirm-dialog-cancel');
        cancelBtn.addEventListener('click', closeConfirmDialog);
        footerEl.appendChild(cancelBtn);
      }
    }
  });

  dialog.showModal();
};

window.closeConfirmDialog = function () {
  var dialog = document.getElementById('confirm-dialog');
  if (dialog) {
    dialog.close();
  }
};

// ── Close any open dialog on HX-Trigger: closeDialog ───────────
document.addEventListener('closeDialog', function () {
  document.querySelectorAll('dialog[open]').forEach(function (d) {
    d.close();
  });
});

// ── Reset list search boxes on HX-Trigger: dialogSuccess ──────
// A dialogSuccess response just OOB-refreshed the list UNFILTERED — the create
// request carried no search term, so the server can't re-apply one. Clear any
// list search box so the visible full list and the box agree; a stale term
// otherwise sits above rows that no longer match it (CR6). Deliberately keyed
// on dialogSuccess, NOT the generic closeDialog: other handlers emit closeDialog
// for closes that don't reset the list (and Cancel/ESC fires neither event), so
// only this outcome may wipe the user's term.
document.addEventListener('dialogSuccess', function () {
  document.querySelectorAll('.search-box input[name="q"]').forEach(function (input) {
    if (input.value) input.value = '';
  });
});

// ── Promise-based confirm (replaces native confirm()) ─────────
//
// Returns a Promise<boolean> that resolves true when the user
// clicks confirm, false on cancel / ESC / backdrop click.
//
// Options:
//   title        — dialog heading (default "Confirm")
//   confirmLabel — confirm button text (default "Delete")
//   confirmClass — confirm button class (default "ep-btn-destructive")
//   cancelLabel  — cancel button text (default "Cancel")
//
window.epistolaConfirm = function (message, options) {
  if (!options) options = {};
  return new Promise(function (resolve) {
    var dialog = document.getElementById('confirm-dialog');
    if (!dialog) {
      resolve(window.confirm(message));
      return;
    }

    var titleEl = document.getElementById('confirm-dialog-title');
    var messageEl = document.getElementById('confirm-dialog-message');
    var errorEl = document.getElementById('confirm-dialog-error');
    var footerEl = document.getElementById('confirm-dialog-footer');

    titleEl.textContent = options.title || 'Confirm';
    messageEl.textContent = message;
    errorEl.style.display = 'none';
    errorEl.textContent = '';

    footerEl.innerHTML = '';

    var cancelBtn = document.createElement('button');
    cancelBtn.type = 'button';
    cancelBtn.className = 'ep-btn ep-btn-ghost';
    cancelBtn.textContent = options.cancelLabel || 'Cancel';
    cancelBtn.setAttribute('data-testid', 'confirm-dialog-cancel');
    footerEl.appendChild(cancelBtn);

    var confirmBtn = document.createElement('button');
    confirmBtn.type = 'button';
    confirmBtn.className = 'ep-btn ' + (options.confirmClass || 'ep-btn-destructive');
    confirmBtn.textContent = options.confirmLabel || 'Delete';
    confirmBtn.setAttribute('data-testid', 'confirm-dialog-confirm');
    footerEl.appendChild(confirmBtn);

    var resolved = false;

    function done(value) {
      if (resolved) return;
      resolved = true;
      dialog.removeEventListener('close', onClose);
      dialog.close();
      resolve(value);
    }

    function onCancel() {
      done(false);
    }
    function onConfirm() {
      done(true);
    }
    function onClose() {
      done(false);
    }

    cancelBtn.addEventListener('click', onCancel);
    confirmBtn.addEventListener('click', onConfirm);
    dialog.addEventListener('close', onClose);

    dialog.showModal();
  });
};

// ── Intercept hx-confirm to use our dialog instead of native ──
document.addEventListener('htmx:confirm', function (event) {
  var question = event.detail && event.detail.question;
  if (!question) return;

  event.preventDefault();

  window
    .epistolaConfirm(question, {
      title: 'Confirm',
      confirmLabel: 'Confirm',
      confirmClass: 'ep-btn-primary',
    })
    .then(function (ok) {
      if (ok) {
        event.detail.issueRequest(true);
      }
    });
});

// ── Global form errors ──────────────────────────────────────────
//
// Every data-entry form carries a [data-form-error] slot (the
// epistola-web/form-error fragment). Handlers report operation-level
// failures with a real 4xx/5xx status plus an OOB fragment replacing the
// slot (HtmxDsl.globalFormError). HTMX ignores error-status bodies by
// default; the HX-Reswap response header marks a response as shaped for
// HTMX, so let it swap (usually "none" — only the OOB fragment
// processes). isError is deliberately left true so htmx:afterRequest
// still reports failure (data-reset-on-success must not fire).
document.addEventListener('htmx:beforeSwap', function (event) {
  var xhr = event.detail.xhr;
  if (!xhr || xhr.status < 400) return;
  if (xhr.getResponseHeader('HX-Reswap')) {
    event.detail.shouldSwap = true;
  }
});

// Clear a form's global error slot whenever the form issues a new request.
document.addEventListener('htmx:beforeRequest', function (event) {
  var elt = event.detail.elt;
  var form = elt && elt.closest ? elt.closest('form') : null;
  if (!form) return;
  form.querySelectorAll('[data-form-error]').forEach(function (el) {
    el.hidden = true;
    el.textContent = '';
  });
});

// ── Global safety net for unhandled errors ──────────────────────

document.addEventListener('htmx:responseError', function (event) {
  var xhr = event.detail.xhr;
  if (!xhr) return;

  // Shaped error responses already delivered their message via the OOB
  // swap above — nothing to add here.
  if (xhr.getResponseHeader('HX-Reswap')) return;

  // Don't show global error if the target is inside the confirm dialog
  var target = event.detail.target;
  if (target && target.closest && target.closest('#confirm-dialog')) return;

  var detail;
  try {
    var body = JSON.parse(xhr.responseText);
    detail = body.detail || body.error;
  } catch (e) {
    detail = null;
  }

  var message;
  if (xhr.status === 403) {
    message = detail || "You don't have permission to perform this action.";
  } else if (xhr.status >= 500) {
    message = 'An unexpected error occurred. Please try again.';
  } else {
    message = detail || 'The request failed. Please try again.';
  }

  // Prefer the issuing form's global error slot over the page banner.
  var sourceForm =
    event.detail.elt && event.detail.elt.closest ? event.detail.elt.closest('form') : null;
  var slot = sourceForm ? sourceForm.querySelector('[data-form-error]') : null;
  if (slot) {
    slot.textContent = message;
    slot.hidden = false;
    return;
  }

  // No slot (row actions, non-form triggers): keep the banner, but only for
  // the statuses it always covered.
  if (xhr.status !== 403 && xhr.status < 500) return;

  // Insert error banner at top of main content
  var main = document.querySelector('#main-content') || document.querySelector('main');
  if (!main) return;

  // Remove any existing global error banner
  var existing = main.querySelector('.global-error-banner');
  if (existing) existing.remove();

  var banner = document.createElement('div');
  banner.className = 'alert alert-error global-error-banner';
  banner.style.marginBottom = 'var(--ep-space-4)';
  var span = document.createElement('span');
  span.textContent = message;
  banner.appendChild(span);
  var closeBtn = document.createElement('button');
  closeBtn.type = 'button';
  closeBtn.className = 'ep-btn ep-btn-sm ep-btn-ghost';
  closeBtn.style.marginLeft = 'auto';
  closeBtn.textContent = '\u00d7';
  closeBtn.addEventListener('click', function () {
    banner.remove();
  });
  banner.appendChild(closeBtn);
  banner.style.display = 'flex';
  banner.style.alignItems = 'center';
  main.insertBefore(banner, main.firstChild);
});
