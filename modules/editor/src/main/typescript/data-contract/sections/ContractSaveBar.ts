// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { html, nothing } from 'lit';

export interface ContractSaveBarState {
  schemaDirty: boolean;
  examplesDirty: boolean;
  saving: boolean;
  saveSuccess: boolean;
  saveError: string | null;
  canForceSave: boolean;
  blockedReason: string | null;
}

export interface ContractSaveBarCallbacks {
  onSave: () => void;
  onForceSave: () => void;
}

export function renderContractSaveBar(
  state: ContractSaveBarState,
  callbacks: ContractSaveBarCallbacks,
): unknown {
  const isDirty = state.schemaDirty || state.examplesDirty;
  const canSave = isDirty && !state.saving && state.blockedReason === null;
  const saveTooltip =
    state.blockedReason ?? (isDirty ? 'Save the complete data contract' : 'No unsaved changes');

  return html`
    <div class="dc-contract-save-bar" role="region" aria-label="Data contract save controls">
      <div class="dc-contract-save-summary" aria-live="polite">
        ${state.saving
          ? html`<span class="dc-contract-save-state">Saving contract…</span>`
          : state.saveError
            ? html`<span class="dc-status-error">${state.saveError}</span>`
            : state.saveSuccess
              ? html`<span class="dc-status-success">Contract saved</span>`
              : isDirty
                ? html`
                    <span class="dc-contract-save-state">Unsaved changes</span>
                    ${state.schemaDirty
                      ? html`<span class="dc-contract-change-badge">Schema</span>`
                      : nothing}
                    ${state.examplesDirty
                      ? html`<span class="dc-contract-change-badge">Examples</span>`
                      : nothing}
                  `
                : html`<span class="dc-contract-save-state dc-contract-save-state-muted"
                    >All changes saved</span
                  >`}
        ${isDirty && state.blockedReason
          ? html`<span class="dc-contract-save-blocked">${state.blockedReason}</span>`
          : nothing}
      </div>

      <div class="dc-contract-save-actions">
        ${state.canForceSave
          ? html`
              <button
                class="ep-btn ep-btn-outline ep-btn-sm dc-force-save-btn"
                ?disabled=${state.saving}
                @click=${() => callbacks.onForceSave()}
              >
                Save anyway
              </button>
            `
          : nothing}
        <button
          class="ep-btn ep-btn-primary ep-btn-sm dc-save-btn"
          ?disabled=${!canSave}
          @click=${() => callbacks.onSave()}
          title=${saveTooltip}
        >
          ${state.saving ? 'Saving…' : 'Save contract'}
        </button>
      </div>
    </div>
  `;
}
