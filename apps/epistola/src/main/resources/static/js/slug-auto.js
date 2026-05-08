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
    var slugId = nameInput.getAttribute('data-slug-source');
    var slugInput = document.getElementById(slugId);
    if (!slugInput) return;

    var slugManuallyEdited = false;

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

  document.querySelectorAll('[data-slug-source]').forEach(wireup);

  var observer = new MutationObserver(function (mutations) {
    mutations.forEach(function (mutation) {
      mutation.addedNodes.forEach(function (node) {
        if (node.nodeType !== Node.ELEMENT_NODE) return;
        if (node.matches && node.matches('[data-slug-source]')) {
          wireup(node);
        }
        if (node.querySelectorAll) {
          node.querySelectorAll('[data-slug-source]').forEach(wireup);
        }
      });
    });
  });

  observer.observe(document.body, { childList: true, subtree: true });
})();
