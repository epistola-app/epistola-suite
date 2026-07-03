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

    if (!e.target.closest('.app-nav-dropdown')) closeAllDropdowns();
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
