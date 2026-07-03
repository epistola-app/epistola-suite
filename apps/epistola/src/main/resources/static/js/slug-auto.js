(function () {
  var ALLOWED_KEYS = ['Backspace', 'Delete', 'Enter', 'Escape', 'Tab', 'Home', 'End'];

  function nameToSlug(name) {
    return name
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '');
  }

  function isValidSlugKey(key) {
    return key.length === 1 && /^[a-z0-9-]$/.test(key);
  }

  function wireup(nameInput) {
    if (nameInput.hasAttribute('data-slug-wired')) return;
    var slugId = nameInput.getAttribute('data-slug-source');
    var slugInput = document.getElementById(slugId);
    if (!slugInput) return;
    nameInput.setAttribute('data-slug-wired', '');

    var slugManuallyEdited =
      slugInput.value !== '' && slugInput.value !== nameToSlug(nameInput.value);

    nameInput.addEventListener('input', function () {
      if (!slugManuallyEdited) {
        slugInput.value = nameToSlug(nameInput.value);
      }
    });

    slugInput.addEventListener('input', function () {
      var cleaned = slugInput.value.replace(/[^a-z0-9-]/g, '');
      if (cleaned !== slugInput.value) {
        slugInput.value = cleaned;
      }
      slugManuallyEdited = slugInput.value !== nameToSlug(nameInput.value);
    });

    slugInput.addEventListener('keydown', function (e) {
      if (
        ALLOWED_KEYS.indexOf(e.key) !== -1 ||
        e.key.startsWith('Arrow') ||
        e.ctrlKey ||
        e.metaKey ||
        e.altKey
      ) {
        return;
      }

      if (!isValidSlugKey(e.key)) {
        e.preventDefault();
      }
    });
  }

  function scan(root) {
    if (root.matches && root.matches('[data-slug-source]')) wireup(root);
    if (root.querySelectorAll) {
      root.querySelectorAll('[data-slug-source]').forEach(wireup);
    }
  }

  // Loaded from <head> (ADR 0010): the body may not exist yet, and content
  // arrives later via HTMX swaps (incl. hx-boost navigations). htmx:load fires
  // for the initial page and for every swapped-in element; the data-slug-wired
  // guard makes re-scans a no-op.
  document.addEventListener('htmx:load', function (event) {
    scan(event.detail.elt);
  });
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () {
      scan(document.body);
    });
  } else if (document.body) {
    scan(document.body);
  }
})();
