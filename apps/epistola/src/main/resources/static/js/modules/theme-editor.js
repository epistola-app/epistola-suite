/**
 * Theme editor module for managing theme styles and presets.
 * Used by themes/detail.html template.
 */
import {patch, showError, showSuccess} from './api-client.js';

let tenantId = null;
let themeId = null;

/**
 * Initializes the theme editor with context data.
 * @param {Object} config
 * @param {string} config.tenantId - The tenant ID
 * @param {string} config.themeId - The theme ID
 * @param {Object} config.initialPresets - Initial block style presets
 */
export function initThemeEditor(config) {
    tenantId = config.tenantId;
    themeId = config.themeId;

    // Render initial presets
    renderPresets(config.initialPresets || {});
}

/**
 * Renders the preset items in the container.
 * @param {Object} presets - Map of preset name to styles
 */
export function renderPresets(presets) {
    const container = document.getElementById('presets-container');
    if (!container) return;

    container.innerHTML = '';

    if (Object.keys(presets).length === 0) {
        container.innerHTML = '<p class="text-muted">No presets defined yet.</p>';
        return;
    }

    for (const [name, styles] of Object.entries(presets)) {
        const presetHtml = createPresetHtml(name, styles);
        container.insertAdjacentHTML('beforeend', presetHtml);
    }
}

/**
 * Creates HTML for a single preset item.
 * @param {string} name - Preset name
 * @param {Object} styles - Style properties
 * @returns {string} HTML string
 */
function createPresetHtml(name, styles) {
    const stylesJson = JSON.stringify(styles, null, 2);
    return `
        <div class="preset-item form-section" style="margin-bottom: 1rem;" data-preset-name="${name}">
            <div class="form-row" style="align-items: flex-start;">
                <div class="form-group" style="flex: 0 0 200px;">
                    <label>Preset Name</label>
                    <input type="text" class="preset-name" value="${name}" placeholder="e.g., heading1">
                </div>
                <div class="form-group" style="flex: 1;">
                    <label>Styles (JSON)</label>
                    <textarea class="preset-styles" rows="3" placeholder='{"fontSize": "24pt", "fontWeight": "bold"}'>${stylesJson}</textarea>
                </div>
                <button type="button" class="btn btn-danger btn-small" style="margin-top: 1.5rem;" onclick="window.themeEditor.removePreset(this)">Remove</button>
            </div>
        </div>
    `;
}

/**
 * Adds a new empty preset to the container.
 */
export function addPreset() {
    const container = document.getElementById('presets-container');
    if (!container) return;

    // Remove "no presets" message if present
    const emptyMsg = container.querySelector('.text-muted');
    if (emptyMsg) {
        emptyMsg.remove();
    }
    container.insertAdjacentHTML('beforeend', createPresetHtml('', {}));
}

/**
 * Removes a preset from the container.
 * @param {HTMLElement} button - The remove button that was clicked
 */
export function removePreset(button) {
    button.closest('.preset-item').remove();

    // Show "no presets" message if empty
    const container = document.getElementById('presets-container');
    if (container && container.children.length === 0) {
        container.innerHTML = '<p class="text-muted">No presets defined yet.</p>';
    }
}

/**
 * Collects all presets from the form.
 * @returns {Object} Map of preset name to styles
 * @throws {Error} If any preset has invalid JSON
 */
function collectPresets() {
    const presets = {};
    document.querySelectorAll('.preset-item').forEach(item => {
        const name = item.querySelector('.preset-name').value.trim();
        const stylesText = item.querySelector('.preset-styles').value.trim();
        if (name && stylesText) {
            try {
                presets[name] = JSON.parse(stylesText);
            } catch (e) {
                alert(`Invalid JSON for preset "${name}": ${e.message}`);
                throw e;
            }
        }
    });
    return presets;
}

/**
 * Saves basic info (name and description) to the server.
 */
export async function saveBasicInfo() {
    const name = document.getElementById('theme-name')?.value.trim();
    const description = document.getElementById('theme-description')?.value.trim();

    const body = {
        name: name || undefined,
        description: description || undefined,
        clearDescription: !description,
    };

    await saveTheme(body);
}

/**
 * Saves document styles to the server.
 */
export async function saveDocumentStyles() {
    const documentStyles = {
        fontFamily: document.getElementById('doc-fontFamily')?.value.trim() || null,
        fontSize: document.getElementById('doc-fontSize')?.value.trim() || null,
        fontWeight: document.getElementById('doc-fontWeight')?.value || null,
        color: document.getElementById('doc-color')?.value.trim() || null,
        lineHeight: document.getElementById('doc-lineHeight')?.value.trim() || null,
        letterSpacing: document.getElementById('doc-letterSpacing')?.value.trim() || null,
        textAlign: document.getElementById('doc-textAlign')?.value || null,
        backgroundColor: document.getElementById('doc-backgroundColor')?.value.trim() || null,
    };

    await saveTheme({ documentStyles });
}

/**
 * Saves block style presets to the server.
 */
export async function savePresets() {
    try {
        const presets = collectPresets();
        await saveTheme({
            blockStylePresets: Object.keys(presets).length > 0 ? presets : undefined,
            clearBlockStylePresets: Object.keys(presets).length === 0,
        });
    } catch {
        // Error already shown in collectPresets
    }
}

/**
 * Saves theme data to the server.
 * @param {Object} body - Request body
 */
async function saveTheme(body) {
    const url = `/tenants/${tenantId}/themes/${themeId}`;
    const result = await patch(url, body);

    if (!result.ok) {
        showError('save', result.error);
        return;
    }

    // Update page title if name changed
    if (result.data?.name) {
        const h1 = document.querySelector('h1');
        if (h1) h1.textContent = result.data.name;
        document.title = result.data.name + ' - Epistola';
    }

    showSuccess('Saved successfully!');
}

// Export for global access from onclick handlers
if (typeof window !== 'undefined') {
    window.themeEditor = {
        addPreset,
        removePreset,
        saveBasicInfo,
        saveDocumentStyles,
        savePresets,
    };
}
