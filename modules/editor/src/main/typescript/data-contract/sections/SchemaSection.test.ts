// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { render } from 'lit';
import { describe, expect, it, vi } from 'vitest';
import {
  renderSchemaSection,
  type SchemaSectionCallbacks,
  type SchemaUiState,
} from './SchemaSection.js';

const uiState: SchemaUiState = {
  warnings: [],
  canUndo: false,
  canRedo: false,
  selectedFieldId: null,
  readOnly: false,
  jsonPanelOpen: false,
};

const callbacks: SchemaSectionCallbacks = {
  onCommand: vi.fn(),
  onToggleFieldExpand: vi.fn(),
  onSelectField: vi.fn(),
  onUndo: vi.fn(),
  onRedo: vi.fn(),
  onAddField: vi.fn(),
  onImport: vi.fn(),
  onToggleJson: vi.fn(),
};

describe('SchemaSection toolbar', () => {
  it('keeps schema actions and undo history local without a section-specific save action', () => {
    const container = document.createElement('div');
    render(renderSchemaSection({ fields: [] }, uiState, callbacks, new Set()), container);

    const toolbar = container.querySelector('.dc-toolbar');
    expect(toolbar?.textContent).toContain('Add Field');
    expect(toolbar?.textContent).toContain('Undo');
    expect(toolbar?.textContent).toContain('Redo');
    expect(toolbar?.querySelector('.dc-save-btn')).toBeNull();
  });
});
