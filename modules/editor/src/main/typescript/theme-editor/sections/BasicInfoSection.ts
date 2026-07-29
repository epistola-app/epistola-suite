// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * BasicInfoSection — Name and description inputs for the theme.
 */

import { html } from 'lit';
import type { ThemeEditorState } from '../ThemeEditorState.js';

export function renderBasicInfoSection(state: ThemeEditorState, readOnly = false): unknown {
  const theme = state.theme;

  return html`
    <div class="theme-section theme-section-basic">
      <h3 class="theme-section-label">Basic Information</h3>
      <div class="inspector-field">
        <label class="inspector-field-label" for="theme-name">Name</label>
        <input
          type="text"
          id="theme-name"
          class="ep-input"
          maxlength="100"
          .value=${theme.name}
          ?disabled=${readOnly}
          @change=${(e: Event) => state.updateName((e.target as HTMLInputElement).value)}
        />
      </div>
      <div class="inspector-field">
        <label class="inspector-field-label" for="theme-description">Description</label>
        <textarea
          class="ep-input ep-textarea"
          id="theme-description"
          rows="2"
          .value=${theme.description ?? ''}
          ?disabled=${readOnly}
          @change=${(e: Event) => {
            const val = (e.target as HTMLTextAreaElement).value;
            state.updateDescription(val || undefined);
          }}
          placeholder="Optional description..."
        ></textarea>
      </div>
    </div>
  `;
}
