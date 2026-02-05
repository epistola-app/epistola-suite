/**
 * Session expiry monitor - warns users before session expires and opens login popup when expired.
 *
 * Reads the session_expires_at cookie (set by SessionExpiryCookieFilter) to track session expiry.
 * Shows a warning dialog 5 minutes before expiry, and an expired dialog with re-login option when expired.
 *
 * The re-login opens a popup window to preserve form data on the main page.
 */

const WARNING_MINUTES = 5;
const CHECK_INTERVAL_MS = 30000; // Check every 30 seconds

let warningShown = false;
let expiredShown = false;
let checkIntervalId = null;

/**
 * Gets the session expiry timestamp from the cookie.
 * @returns {number|null} Unix timestamp in milliseconds, or null if not found
 */
function getSessionExpiry() {
    const match = document.cookie.match(/session_expires_at=(\d+)/);
    return match ? parseInt(match[1], 10) : null;
}

/**
 * Gets time remaining until session expires.
 * @returns {number} Milliseconds remaining, or Infinity if no expiry cookie
 */
function getTimeRemaining() {
    const expiry = getSessionExpiry();
    return expiry ? expiry - Date.now() : Infinity;
}

/**
 * Shows the warning dialog (session expiring soon).
 */
function showWarningDialog() {
    if (warningShown) return;
    warningShown = true;

    const dialog = document.getElementById('session-warning-dialog');
    if (dialog && typeof dialog.showModal === 'function') {
        dialog.showModal();
    }
}

/**
 * Shows the expired dialog (session has expired).
 */
function showExpiredDialog() {
    if (expiredShown) return;
    expiredShown = true;

    // Hide warning dialog if shown
    const warningDialog = document.getElementById('session-warning-dialog');
    if (warningDialog && warningDialog.open) {
        warningDialog.close();
    }

    const expiredDialog = document.getElementById('session-expired-dialog');
    if (expiredDialog && typeof expiredDialog.showModal === 'function') {
        expiredDialog.showModal();
    }
}

/**
 * Opens the login popup window.
 */
function openLoginPopup() {
    const popup = window.open(
        '/login?popup=true',
        'epistola-login',
        'width=450,height=600,menubar=no,toolbar=no,location=no,resizable=yes'
    );

    // Focus popup if already open
    if (popup) {
        popup.focus();
    }
}

/**
 * Handles successful session renewal from popup.
 */
function handleSessionRenewed() {
    const expiredDialog = document.getElementById('session-expired-dialog');
    if (expiredDialog && expiredDialog.open) {
        expiredDialog.close();
    }

    const warningDialog = document.getElementById('session-warning-dialog');
    if (warningDialog && warningDialog.open) {
        warningDialog.close();
    }

    warningShown = false;
    expiredShown = false;
}

/**
 * Dismisses the warning dialog.
 */
function dismissWarning() {
    const dialog = document.getElementById('session-warning-dialog');
    if (dialog && dialog.open) {
        dialog.close();
    }
}

/**
 * Dismisses the expired dialog (user claims they've logged in elsewhere).
 */
function dismissExpired() {
    const dialog = document.getElementById('session-expired-dialog');
    if (dialog && dialog.open) {
        dialog.close();
    }
    // Reset state so dialogs can show again if needed
    warningShown = false;
    expiredShown = false;
}

/**
 * Checks session status and shows appropriate dialogs.
 */
function checkSession() {
    const remaining = getTimeRemaining();
    const warningThreshold = WARNING_MINUTES * 60 * 1000;

    if (remaining <= 0) {
        showExpiredDialog();
    } else if (remaining <= warningThreshold && !warningShown) {
        showWarningDialog();
    }
}

/**
 * Initializes the session monitor.
 * Should be called on page load for authenticated users.
 */
export function initSessionMonitor() {
    // Listen for login success from popup
    window.addEventListener('message', (event) => {
        // Only accept messages from same origin
        if (event.origin !== window.location.origin) return;

        if (event.data?.type === 'session-renewed') {
            handleSessionRenewed();
        }
    });

    // Expose functions for dialog buttons
    window.epistola = window.epistola || {};
    window.epistola.openLoginPopup = openLoginPopup;
    window.epistola.dismissWarning = dismissWarning;
    window.epistola.dismissExpired = dismissExpired;

    // Start periodic checks
    checkIntervalId = setInterval(checkSession, CHECK_INTERVAL_MS);

    // Initial check
    checkSession();
}

/**
 * Stops the session monitor.
 * Useful if the user logs out via other means.
 */
export function stopSessionMonitor() {
    if (checkIntervalId) {
        clearInterval(checkIntervalId);
        checkIntervalId = null;
    }
}
