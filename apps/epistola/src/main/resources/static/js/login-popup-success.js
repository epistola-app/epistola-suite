// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// Login-popup success page: notify the opener that the session was renewed,
// then close the popup. Loaded only by login-popup-success.html (always a hard
// load — popup windows are never hx-boosted).
(function () {
  if (window.opener) {
    try {
      window.opener.postMessage({ type: 'session-renewed' }, window.location.origin);
    } catch (e) {
      // Ignore cross-origin errors
    }
  }

  // Small delay so the message is sent before the window goes away.
  setTimeout(function () {
    window.close();

    // If window.close() didn't work (some browsers restrict it), tell the user.
    var notice = document.querySelector('.close-notice');
    if (notice) notice.textContent = 'You can close this window and return to your work.';
  }, 500);
})();
