// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// Navigation behavior: dropdown keyboard support + mobile hamburger menu.
// Fully delegated (ADR 0010): survives hx-boost body swaps without re-wiring.
(function () {
  function closeAllDropdowns() {
    document.querySelectorAll('.app-nav-dropdown.open').forEach(function (d) {
      d.classList.remove('open');
      var t = d.querySelector('.app-nav-dropdown-trigger');
      if (t) t.setAttribute('aria-expanded', 'false');
    });
  }

  function openDropdown(dropdown) {
    dropdown.classList.add('open');
    var t = dropdown.querySelector('.app-nav-dropdown-trigger');
    if (t) t.setAttribute('aria-expanded', 'true');
    var first = dropdown.querySelector('.app-nav-dropdown-menu [role="menuitem"]');
    if (first) first.focus();
  }

  function closeMobile() {
    var nav = document.getElementById('app-nav');
    if (!nav) return null;
    nav.classList.remove('nav-open');
    var hamburger = nav.querySelector('.app-nav-hamburger');
    if (hamburger) hamburger.setAttribute('aria-expanded', 'false');
    return hamburger;
  }

  document.addEventListener('click', function (e) {
    if (!e.target.closest) return;

    var trigger = e.target.closest('.app-nav-dropdown-trigger');
    if (trigger) {
      var dropdown = trigger.closest('.app-nav-dropdown');
      var isOpen = dropdown.classList.contains('open');
      closeAllDropdowns();
      if (!isOpen) openDropdown(dropdown);
      return;
    }

    var hamburger = e.target.closest('.app-nav-hamburger');
    if (hamburger) {
      var nav = document.getElementById('app-nav');
      var isOpen = nav.classList.toggle('nav-open');
      hamburger.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
      return;
    }

    if (e.target.closest('.app-nav-mobile-backdrop')) {
      closeMobile();
      return;
    }

    // Switching catalog versions keeps the page: the menu's links are the same on every page, so the
    // page someone is on travels with the click (VersionSwitch on the server).
    var versionLink = e.target.closest('a[data-version-switch]');
    if (versionLink) {
      var url = new URL(versionLink.href, window.location.origin);
      url.searchParams.set('from', window.location.pathname + window.location.search);
      versionLink.href = url.pathname + url.search;
    }

    if (!e.target.closest('.app-nav-dropdown')) closeAllDropdowns();
  });

  // A boosted navigation inside the workspace replaces only the page (docs/workspace-shell.md):
  // say which bar this page shows, so the server sends the bar again only when it changes.
  document.addEventListener('htmx:configRequest', function (e) {
    if (e.detail.headers['HX-Boosted'] !== 'true') return;
    var key = document.getElementById('ws-shell-key');
    if (key) e.detail.headers['X-Epistola-Shell'] = key.getAttribute('data-key');
  });

  // A save on the page (any request but a GET) can change what is not published yet: refresh the
  // bar's status, so an inline edit shows in it without reloading the page.
  document.addEventListener('htmx:afterRequest', function (e) {
    var config = e.detail.requestConfig;
    var verb = config && config.verb ? String(config.verb).toLowerCase() : 'get';
    if (!e.detail.successful || verb === 'get') return;
    if (document.getElementById('ws-status') && window.htmx)
      window.htmx.trigger(document.body, 'ws-status-refresh');
  });

  // When the bar is replaced for the same catalog version, show its status as it was until the
  // fresh one arrives, instead of an empty space.
  var keptStatus = null;
  document.addEventListener('htmx:beforeSwap', function () {
    var status = document.getElementById('ws-status');
    keptStatus = status ? { href: status.getAttribute('hx-get'), html: status.innerHTML } : null;
  });
  document.addEventListener('htmx:afterSwap', function () {
    var status = document.getElementById('ws-status');
    if (
      keptStatus &&
      status &&
      status.getAttribute('hx-get') === keptStatus.href &&
      !status.innerHTML.trim()
    ) {
      status.innerHTML = keptStatus.html;
      if (window.htmx) window.htmx.process(status);
    }
    keptStatus = null;
  });

  document.addEventListener('keydown', function (e) {
    if (!e.target.closest) return;

    var trigger = e.target.closest('.app-nav-dropdown-trigger');
    if (trigger && e.key === 'ArrowDown') {
      e.preventDefault();
      closeAllDropdowns();
      openDropdown(trigger.closest('.app-nav-dropdown'));
      return;
    }

    var menu = e.target.closest('.app-nav-dropdown-menu');
    if (menu) {
      var items = Array.prototype.slice.call(menu.querySelectorAll('[role="menuitem"]'));
      var idx = items.indexOf(document.activeElement);
      var dropdown = menu.closest('.app-nav-dropdown');
      var dropdownTrigger = dropdown && dropdown.querySelector('.app-nav-dropdown-trigger');
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        if (idx < items.length - 1) items[idx + 1].focus();
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        if (idx > 0) items[idx - 1].focus();
        else if (dropdownTrigger) dropdownTrigger.focus();
      } else if (e.key === 'Escape') {
        e.preventDefault();
        closeAllDropdowns();
        if (dropdownTrigger) dropdownTrigger.focus();
      }
      return;
    }

    if (e.key === 'Escape') {
      var nav = document.getElementById('app-nav');
      if (nav && nav.classList.contains('nav-open')) {
        var hamburger = closeMobile();
        if (hamburger) hamburger.focus();
      }
    }
  });
})();
