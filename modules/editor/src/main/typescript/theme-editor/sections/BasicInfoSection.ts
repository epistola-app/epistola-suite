/**
 * BasicInfoSection — Name and description inputs for the theme.
 */

import { html } from 'lit'
import type { ThemeEditorState } from '../ThemeEditorState.js'

export function renderBasicInfoSection(state: ThemeEditorState): unknown {
  const theme = state.theme

  return html`
    <section class="theme-section">
      <h3 class="theme-section-label">Basic Information</h3>
      <div class="inspector-field">
        <label class="inspector-field-label">Name</label>
        <input
          type="text"
          class="ep-input"
          .value=${theme.name}
          @change=${(e: Event) => state.updateName((e.target as HTMLInputElement).value)}
        />
      </div>
      <div class="inspector-field">
        <label class="inspector-field-label">Description</label>
        <textarea
          class="ep-input ep-textarea"
          rows="2"
          .value=${theme.description ?? ''}
          @change=${(e: Event) => {
            const val = (e.target as HTMLTextAreaElement).value
            state.updateDescription(val || undefined)
          }}
          placeholder="Optional description..."
        ></textarea>
      </div>
    </section>
  `
}
