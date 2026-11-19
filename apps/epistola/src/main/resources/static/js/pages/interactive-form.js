// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// The interactive generation form page (contract ADR 0011). Mounts the form the server described
// (a FormDefinition; today generated from the data contract) with the data so far, keeps the
// hidden `data` field of the submit form in step, and renders a preview on request. The server
// validates every submission and preview; nothing here is trusted.

const page = document.querySelector('[data-interactive-form]');
if (page) {
  const { mountDataForm } = await import('/editor/data-form.js');
  const requestId = page.dataset.requestId;
  const definition = JSON.parse(document.getElementById('form-definition').textContent);
  const problems = JSON.parse(document.getElementById('form-problems').textContent);
  const guard = document.getElementById('form-guard').value;
  const dataField = page.querySelector('[data-form-data]');

  const form = mountDataForm({
    container: page.querySelector('[data-form-fields]'),
    definition,
    data: JSON.parse(dataField.value || '{}'),
    problems,
    onChange: (data) => {
      dataField.value = JSON.stringify(data);
    },
  });

  const pane = page.querySelector('[data-form-preview-pane]');
  const frame = page.querySelector('[data-form-preview-frame]');
  const message = page.querySelector('[data-form-preview-message]');
  let previewUrl = null;

  page.querySelector('[data-form-preview]').addEventListener('click', async () => {
    pane.hidden = false;
    message.textContent = 'Rendering the preview…';
    const response = await fetch(`/forms/${requestId}/preview`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-Form-Guard': guard },
      body: JSON.stringify(form.data()),
    });
    if (response.ok) {
      if (previewUrl) URL.revokeObjectURL(previewUrl);
      previewUrl = URL.createObjectURL(await response.blob());
      frame.src = previewUrl;
      frame.hidden = false;
      message.textContent = '';
    } else if (response.status === 422) {
      const body = await response.json();
      form.showProblems(body.problems);
      frame.hidden = true;
      message.textContent = 'Fill in the marked fields to see a preview.';
    } else if (response.status === 429) {
      message.textContent = 'Too many previews in a short time. Try again in a few seconds.';
    } else {
      message.textContent = 'This form can no longer be previewed. Reload the page.';
    }
  });
}
