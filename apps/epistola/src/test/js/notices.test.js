// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// Unit tests for the corner-notice subsystem (static/js/notices.js).
//
// The production file is a classic script (no exports — it wires document
// listeners and window.epistolaNotice), so the real file is evaluated once
// into the happy-dom page and driven through the same surfaces the app uses:
// the public API, DOM events, and synthetic htmx events (htmx events are
// plain CustomEvents; the safety net only reads event.detail, which tests
// can stub). Timer behavior runs on vitest fake timers — the deterministic
// substitute for the wall-clock waits the UI-test hygiene rules ban.
//
// Deliberately NOT covered here: the notices-above-modal-dialogs placement
// (popover reparenting + the MutationObserver rescue). happy-dom has no
// popover API, and notices.js guards on `showPopover` being available, so
// that whole section self-disables in this environment. It stays covered by
// browser tests, where the top layer actually exists.

import { readFileSync } from 'node:fs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const NOTICE_TIMEOUT_MS = 5000; // mirrors notices.js
const NOTICE_TICK_MS = 250; // mirrors notices.js (sweep interval)
const LEAVE_FALLBACK_MS = 300; // dismiss animationend fallback (never fires in happy-dom)

// Evaluate the real script once (indirect eval → global scope, like a
// <script> tag). Its listeners attach to the document once; every test
// rebuilds the page markup, which the script only looks up at event time.
// cwd-relative: vitest runs from the package root, and import.meta.url is
// rewritten to an http URL under the happy-dom environment.
const noticesJs = readFileSync('src/main/resources/static/js/notices.js', 'utf8');
(0, eval)(noticesJs);

// The rendered epistola-web/notice fragment (template pair from layout/shell):
// insertNotice clones this, so the fixture must match the real markup.
function buildPage() {
  document.body.innerHTML = `
    <div id="notices" class="notice-region" aria-live="polite"></div>
    <template id="notice-template">
      <div class="alert alert-error notice" role="alert" data-notice>
        <div class="notice-body">
          <div class="alert-title"></div>
          <span class="notice-message"></span>
        </div>
        <button type="button" class="notice-dismiss" data-notice-dismiss aria-label="Dismiss">&times;</button>
      </div>
    </template>
  `;
}

function notices() {
  return Array.from(document.querySelectorAll('#notices .notice'));
}

function fakeXhr(status, body, headers = {}) {
  return {
    status,
    responseText: body ?? '',
    getResponseHeader: (name) => headers[name] ?? null,
  };
}

function fireHtmx(type, detail) {
  document.dispatchEvent(new CustomEvent(type, { detail }));
}

function fireResponseError(xhr, { elt = null, path = '/tenants/demo/things' } = {}) {
  fireHtmx('htmx:responseError', { xhr, elt, pathInfo: { requestPath: path } });
}

beforeEach(() => {
  vi.useFakeTimers();
  buildPage();
});

afterEach(() => {
  // Drain the subsystem within THIS test's fake clock: dismiss whatever is
  // left and give the sweeper one tick to observe the empty map and stop its
  // shared interval. Without this the ticker id (created under this clock)
  // leaks into the next test's fresh clock, where mountNotice sees a live
  // ticker and never schedules one — and nothing ever sweeps. A real page
  // has one clock for its whole life, so only tests can hit this.
  document.querySelectorAll('[data-notice-dismiss]').forEach((b) => b.click());
  vi.advanceTimersByTime(LEAVE_FALLBACK_MS + NOTICE_TICK_MS);
  vi.useRealTimers();
});

describe('epistolaNotice API', () => {
  it('renders a success notice politely with success severity', () => {
    window.epistolaNotice.success('Saved.');

    const [notice] = notices();
    expect(notice.classList.contains('alert-success')).toBe(true);
    expect(notice.classList.contains('alert-error')).toBe(false);
    expect(notice.getAttribute('role')).toBe('status');
    expect(notice.querySelector('.notice-message').textContent).toBe('Saved.');
  });

  it('renders an error notice assertively with error severity', () => {
    window.epistolaNotice.error('It broke.');

    const [notice] = notices();
    expect(notice.classList.contains('alert-error')).toBe(true);
    expect(notice.getAttribute('role')).toBe('alert');
  });

  it('stacks unkeyed notices newest-first', () => {
    window.epistolaNotice.success('First.');
    window.epistolaNotice.success('Second.');

    const texts = notices().map((n) => n.querySelector('.notice-message').textContent);
    expect(texts).toEqual(['Second.', 'First.']);
  });

  it('renders an optional title and drops the slot without one', () => {
    window.epistolaNotice.error('Could not check for upgrades.', { title: 'Stencils' });
    window.epistolaNotice.success('Saved.');

    const [untitled, titled] = notices();
    expect(titled.querySelector('.alert-title').textContent).toBe('Stencils');
    expect(untitled.querySelector('.alert-title')).toBeNull();
  });

  it('dedupes notices sharing a dedupeKey instead of stacking', () => {
    window.epistolaNotice.error('Poll failed.', { dedupeKey: 'upgrade-check' });
    window.epistolaNotice.error('Poll failed.', { dedupeKey: 'upgrade-check' });
    window.epistolaNotice.error('Unrelated.', { dedupeKey: 'other' });

    expect(notices()).toHaveLength(2);
  });

  it('returns a handle that dismisses the notice early', () => {
    const handle = window.epistolaNotice.success('Retract me.');

    handle.dismiss();
    vi.advanceTimersByTime(LEAVE_FALLBACK_MS);
    expect(notices()).toHaveLength(0);
  });

  it('returns a handle for the refreshed notice on a dedupe hit', () => {
    window.epistolaNotice.error('Poll failed.', { dedupeKey: 'poll' });
    const handle = window.epistolaNotice.error('Poll failed.', { dedupeKey: 'poll' });

    handle.dismiss();
    vi.advanceTimersByTime(LEAVE_FALLBACK_MS);
    expect(notices()).toHaveLength(0);
  });

  it('returns null and stays inert when the page hosts no notice region', () => {
    document.body.innerHTML = '<main></main>';

    expect(window.epistolaNotice.success('Nowhere to go.')).toBeNull();
    expect(document.querySelectorAll('.notice')).toHaveLength(0);
  });
});

describe('auto-dismiss', () => {
  it('dismisses a notice after the uniform timeout', () => {
    window.epistolaNotice.success('Bye soon.');
    expect(notices()).toHaveLength(1);

    vi.advanceTimersByTime(NOTICE_TIMEOUT_MS);
    expect(notices()[0].classList.contains('notice-leaving')).toBe(true);

    vi.advanceTimersByTime(LEAVE_FALLBACK_MS);
    expect(notices()).toHaveLength(0);
  });

  it('pauses while hovered and resumes the remaining time on leave', () => {
    window.epistolaNotice.success('Hover me.');
    const [notice] = notices();

    // Hover at t=2s: the deadline check at t=5s must reschedule, not dismiss.
    vi.advanceTimersByTime(2000);
    notice.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
    vi.advanceTimersByTime(NOTICE_TIMEOUT_MS * 2);
    expect(notices()).toHaveLength(1);

    // Leave: the remaining 3s resume from now.
    notice.dispatchEvent(new MouseEvent('mouseout', { bubbles: true }));
    vi.advanceTimersByTime(2999);
    expect(notices()[0].classList.contains('notice-leaving')).toBe(false);
    vi.advanceTimersByTime(1 + LEAVE_FALLBACK_MS);
    expect(notices()).toHaveLength(0);
  });

  it('dismisses early via the dismiss button', () => {
    window.epistolaNotice.error('Dismiss me.');

    notices()[0].querySelector('[data-notice-dismiss]').click();
    vi.advanceTimersByTime(LEAVE_FALLBACK_MS);
    expect(notices()).toHaveLength(0);
  });
});

describe('server-sent notice adoption (htmx:load)', () => {
  // A server-sent notice arrives as an OOB afterbegin swap — htmx inserts the
  // rendered fragment directly, bypassing insertNotice; the htmx:load pass
  // must adopt it into the timing map or it would never auto-dismiss.
  const SERVER_NOTICE_HTML = `
    <div class="alert alert-success notice" role="status" data-notice>
      <div class="notice-body"><span class="notice-message">Saved on the server.</span></div>
      <button type="button" class="notice-dismiss" data-notice-dismiss aria-label="Dismiss">&times;</button>
    </div>
  `;

  it('adopts an OOB-swapped notice into the auto-dismiss lifecycle', () => {
    document.getElementById('notices').insertAdjacentHTML('afterbegin', SERVER_NOTICE_HTML);
    document.dispatchEvent(new CustomEvent('htmx:load'));

    // Hover-pause only engages for adopted (map-tracked) notices, so the
    // attribute appearing proves the adoption itself.
    const [notice] = notices();
    notice.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
    expect(notice.hasAttribute('data-notice-hovered')).toBe(true);

    notice.dispatchEvent(new MouseEvent('mouseout', { bubbles: true }));
    vi.advanceTimersByTime(NOTICE_TIMEOUT_MS + LEAVE_FALLBACK_MS);
    expect(notices()).toHaveLength(0);
  });

  it('does not re-adopt a dismissed notice still animating out', () => {
    window.epistolaNotice.success('Leaving.');
    const [notice] = notices();
    notice.querySelector('[data-notice-dismiss]').click();
    expect(notice.classList.contains('notice-leaving')).toBe(true);

    document.dispatchEvent(new CustomEvent('htmx:load'));

    // Re-adoption would put the leaving notice back in the map, where hover
    // would mark it — the guard must leave it untracked and on its way out.
    notice.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
    expect(notice.hasAttribute('data-notice-hovered')).toBe(false);

    vi.advanceTimersByTime(LEAVE_FALLBACK_MS);
    expect(notices()).toHaveLength(0);
  });
});

describe('error safety net', () => {
  it('turns an unshaped 500 into a generic error notice', () => {
    fireResponseError(fakeXhr(500, 'Internal Server Error'));

    const [notice] = notices();
    expect(notice.querySelector('.notice-message').textContent).toBe(
      'An unexpected error occurred. Please try again.',
    );
    expect(notice.getAttribute('role')).toBe('alert');
  });

  it('gives 403 a permission message', () => {
    fireResponseError(fakeXhr(403, ''));

    expect(notices()[0].querySelector('.notice-message').textContent).toBe(
      "You don't have permission to perform this action.",
    );
  });

  it('surfaces the problem detail of an unshaped 4xx', () => {
    fireResponseError(fakeXhr(422, JSON.stringify({ detail: 'Name is already taken.' })));

    expect(notices()[0].querySelector('.notice-message').textContent).toBe(
      'Name is already taken.',
    );
  });

  it('stays silent for responses shaped with HX-Reswap', () => {
    fireResponseError(fakeXhr(422, '', { 'HX-Reswap': 'none' }));

    expect(notices()).toHaveLength(0);
  });

  it('prefers the issuing form’s error slot over a corner notice', () => {
    document.body.insertAdjacentHTML(
      'beforeend',
      '<form><input name="x" /><div data-form-error hidden></div></form>',
    );
    const form = document.querySelector('form');

    fireResponseError(fakeXhr(422, JSON.stringify({ detail: 'Bad input.' })), {
      elt: form.querySelector('input'),
    });

    const slot = form.querySelector('[data-form-error]');
    expect(slot.hidden).toBe(false);
    expect(slot.textContent).toBe('Bad input.');
    expect(notices()).toHaveLength(0);
  });

  it('stays out of the confirm dialog, which reports failures itself', () => {
    document.body.insertAdjacentHTML(
      'beforeend',
      '<dialog id="confirm-dialog"><form><button>Delete</button></form></dialog>',
    );

    fireResponseError(fakeXhr(500, ''), {
      elt: document.querySelector('#confirm-dialog button'),
    });

    expect(notices()).toHaveLength(0);
  });

  it('dedupes a recurring failure into one refreshed notice', () => {
    const path = '/tenants/demo/poll';
    fireResponseError(fakeXhr(500, ''), { path });
    vi.advanceTimersByTime(2000);
    fireResponseError(fakeXhr(500, ''), { path });

    expect(notices()).toHaveLength(1);
    // The refresh bumped the deadline: a full timeout must elapse from the
    // SECOND failure before dismissal, not from the first.
    vi.advanceTimersByTime(NOTICE_TIMEOUT_MS - 1);
    expect(notices()[0].classList.contains('notice-leaving')).toBe(false);
    vi.advanceTimersByTime(1 + LEAVE_FALLBACK_MS);
    expect(notices()).toHaveLength(0);
  });

  it('updates the deduped notice text when the failure message changes', () => {
    const path = '/tenants/demo/poll';
    fireResponseError(fakeXhr(422, JSON.stringify({ detail: 'First reason.' })), { path });
    fireResponseError(fakeXhr(422, JSON.stringify({ detail: 'Second reason.' })), { path });

    expect(notices()).toHaveLength(1);
    expect(notices()[0].querySelector('.notice-message').textContent).toBe('Second reason.');
  });

  it('reports a network failure that never reached the server', () => {
    fireHtmx('htmx:sendError', {
      elt: null,
      pathInfo: { requestPath: '/tenants/demo/things' },
    });

    expect(notices()[0].querySelector('.notice-message').textContent).toBe(
      'Network error — check your connection and try again.',
    );
  });

  it('reports a response that failed to swap', () => {
    fireHtmx('htmx:swapError', {
      elt: null,
      pathInfo: { requestPath: '/tenants/demo/things' },
    });

    expect(notices()[0].querySelector('.notice-message').textContent).toBe(
      'Something went wrong displaying the response. Reload the page.',
    );
  });
});
