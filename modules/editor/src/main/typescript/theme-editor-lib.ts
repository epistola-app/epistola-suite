// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * @module @epistola/editor/theme-editor
 *
 * Epistola Theme Editor — Lit-based theme editor with visual style controls.
 *
 * Public API:
 *   mountThemeEditor(options)  → ThemeEditorInstance
 */

import './theme-editor/theme-editor.css';
import './theme-editor/EpistolaThemeEditor.js';
import type { ThemeData } from './theme-editor/ThemeEditorState.js';
import { setFontCatalog, setFontDeclarer, type FontInfo } from './engine/font-catalog.js';

export type { ThemeData } from './theme-editor/ThemeEditorState.js';
export type { FontInfo } from './engine/font-catalog.js';
export {
  isReleasedVersionProblem,
  nextDraftLocation,
  offerNextDraft,
} from './ui/released-version.js';

// ---------------------------------------------------------------------------
// Public mount API
// ---------------------------------------------------------------------------

export interface ThemeEditorOptions {
  /** DOM element to mount the editor into */
  container: HTMLElement;
  /** Initial theme data from the backend */
  theme: ThemeData;
  /** Callback when saving — receives the minimal PATCH payload */
  onSave: (payload: object) => Promise<void>;
  /** When true, all inputs are disabled and saving is suppressed */
  readonly?: boolean;
  /**
   * Optional backend-driven font picker. The host fetches the tenant's font
   * catalog; the theme editor's font-family selects (document styles and
   * block-style presets) are populated from it and the matching
   * `@font-face` rules are injected.
   */
  fontOptions?: {
    listFonts: () => Promise<FontInfo[]>;
    /**
     * Makes the editing catalog depend on [catalog] (an address) once the author confirms; true
     * when it was declared. Absent: fonts of catalogs it does not declare are not offered.
     */
    declareCatalog?: (catalog: string, catalogKey: string) => Promise<boolean>;
  };
}

export interface ThemeEditorInstance {
  /** Tear down the editor and clean up */
  unmount(): void;
}

/**
 * Mount the theme editor into a DOM element.
 */
export function mountThemeEditor(options: ThemeEditorOptions): ThemeEditorInstance {
  const { container, theme, onSave, readonly } = options;

  const editorEl = document.createElement('epistola-theme-editor');
  editorEl.style.display = 'block';

  editorEl.init(theme, onSave, readonly);

  // Backend-driven font picker — mutates the live style-registry options and
  // injects `@font-face`; a re-render picks the new options up.
  if (options.fontOptions) {
    const { listFonts, declareCatalog } = options.fontOptions;
    const loadFonts = () =>
      listFonts().then((fonts) => {
        setFontCatalog(fonts);
        editorEl.requestUpdate();
      });
    // A font of a catalog the editing catalog does not declare declares it first; the list is
    // read again, so its fonts move in with those it can use.
    setFontDeclarer(
      declareCatalog
        ? async (font) => {
            const declared = await declareCatalog(font.catalog ?? font.catalogKey, font.catalogKey);
            if (declared)
              await loadFonts().catch((e) => console.warn('Failed to reload fonts:', e));
            return declared;
          }
        : null,
    );
    loadFonts().catch((e) => console.warn('Failed to load font catalog:', e));
  }

  container.innerHTML = '';
  container.appendChild(editorEl);

  return {
    unmount() {
      editorEl.remove();
    },
  };
}
