// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// The interactive generation form page (contract ADR 0011). Mounts the form the server described
// (a FormDefinition; today generated from the data contract) with the data so far, keeps the
// hidden `data` field of the submit form in step, says how many required fields are left, and
// renders a preview: by itself on wide screens once the required fields are filled in and the
// person pauses, and on request. The server validates every submission and preview; nothing here
// is trusted.

const page = document.querySelector('[data-interactive-form]');
if (page) {
  const { mountDataForm } = await import('/editor/data-form.js');
  const requestId = page.dataset.requestId;
  const definition = JSON.parse(document.getElementById('form-definition').textContent);
  const problems = JSON.parse(document.getElementById('form-problems').textContent);
  const guard = document.getElementById('form-guard').value;
  const dataField = page.querySelector('[data-form-data]');
  const progress = page.querySelector('[data-form-progress]');
  const pane = page.querySelector('[data-form-preview-pane]');
  const frame = page.querySelector('[data-form-preview-frame]');
  const message = page.querySelector('[data-form-preview-message]');
  const wide = window.matchMedia('(min-width: 1024px)');

  /** How long the person pauses before the preview renders by itself. */
  const AUTO_PREVIEW_MS = 1500;
  let previewUrl = null;
  let previewed = null;
  let timer = null;

  const showProgress = () => {
    const left = form.missing().length;
    progress.toggleAttribute('data-done', left === 0);
    progress.textContent =
      left === 0
        ? 'Everything required is filled in.'
        : left === 1
          ? '1 required field left'
          : `${left} required fields left`;
  };

  const preview = async () => {
    clearTimeout(timer);
    const body = JSON.stringify(form.data());
    previewed = body;
    message.textContent = 'Rendering the preview…';
    const response = await fetch(`/forms/${requestId}/preview`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-Form-Guard': guard },
      body,
    });
    if (response.ok) {
      if (previewUrl) URL.revokeObjectURL(previewUrl);
      previewUrl = URL.createObjectURL(await response.blob());
      frame.src = previewUrl;
      frame.hidden = false;
      message.textContent = '';
    } else if (response.status === 422) {
      const answer = await response.json();
      form.showProblems(answer.problems);
      frame.hidden = true;
      message.textContent = 'Fill in the marked fields to see a preview.';
    } else if (response.status === 429) {
      message.textContent = 'Too many previews in a short time. Try again in a few seconds.';
    } else {
      message.textContent = 'This form can no longer be previewed. Reload the page.';
    }
  };

  const form = mountDataForm({
    container: page.querySelector('[data-form-fields]'),
    definition,
    data: JSON.parse(dataField.value || '{}'),
    problems,
    submitted: page.dataset.submitted === 'true',
    onChange: (data) => {
      dataField.value = JSON.stringify(data);
      showProgress();
      clearTimeout(timer);
      if (wide.matches && form.missing().length === 0) {
        timer = setTimeout(() => {
          if (JSON.stringify(form.data()) !== previewed) preview();
        }, AUTO_PREVIEW_MS);
      }
    },
  });
  showProgress();

  // Wide screens: a first preview straight away when nothing required is missing.
  if (wide.matches && form.missing().length === 0) preview();

  page.querySelector('[data-form-preview-refresh]').addEventListener('click', preview);
  page.querySelector('[data-form-preview]').addEventListener('click', () => {
    pane.toggleAttribute('data-open', true);
    preview();
    pane.scrollIntoView({ behavior: 'smooth', block: 'start' });
  });

  // A submit with required fields left marks them here, rather than a round trip to say so.
  page.querySelector('[data-form-submit]').addEventListener('submit', (event) => {
    if (form.missing().length > 0) {
      event.preventDefault();
      form.revealProblems();
    }
  });

  // The expiry in the reader's own time and language.
  const expiry = page.querySelector('[data-form-expiry]');
  if (expiry) {
    const at = new Date(expiry.getAttribute('datetime'));
    if (!Number.isNaN(at.getTime())) {
      expiry.textContent = new Intl.DateTimeFormat(document.documentElement.lang || undefined, {
        dateStyle: 'long',
        timeStyle: 'short',
      }).format(at);
    }
  }
}
