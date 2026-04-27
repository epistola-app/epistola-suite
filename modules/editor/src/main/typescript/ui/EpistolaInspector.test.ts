import { beforeEach, describe, expect, it } from 'vitest';
import { EpistolaInspector } from './EpistolaInspector.js';
import { EditorEngine } from '../engine/EditorEngine.js';
import { nodeId, resetCounter, slotId, testRegistry } from '../engine/test-helpers.js';
import type { ComponentDefinition, ComponentRegistry } from '../engine/registry.js';
import type { NodeId, TemplateDocument } from '../types/index.js';

beforeEach(() => {
  resetCounter();
});

/**
 * Recursively serialise a Lit `TemplateResult` (or any render value) to a flat
 * string so tests can assert on rendered content without a real DOM. Handles
 * nested templates, the `nothing` sentinel (a Symbol), and `.map` arrays.
 */
function templateToHtml(value: unknown): string {
  if (value == null || value === false) return '';
  if (typeof value === 'symbol') return ''; // Lit's `nothing`
  if (typeof value === 'string' || typeof value === 'number') return String(value);
  if (Array.isArray(value)) return value.map(templateToHtml).join('');
  if (typeof value === 'object' && 'strings' in value && 'values' in value) {
    const tr = value as { strings: ArrayLike<string>; values: unknown[] };
    return Array.from(tr.strings)
      .map((s, i) => s + (i < tr.values.length ? templateToHtml(tr.values[i]) : ''))
      .join('');
  }
  return '';
}

interface SetupResult {
  engine: EditorEngine;
  inspector: EpistolaInspector;
  textNodeId: NodeId;
  registry: ComponentRegistry;
}

function setupInspector(): SetupResult {
  const rootId = nodeId('root');
  const rootSlotId = slotId('root-slot');
  const textNodeId = nodeId('text1');

  const doc: TemplateDocument = {
    modelVersion: 1,
    root: rootId,
    nodes: {
      [rootId]: { id: rootId, type: 'root', slots: [rootSlotId] },
      [textNodeId]: {
        id: textNodeId,
        type: 'text',
        slots: [],
        props: { content: null },
      },
    },
    slots: {
      [rootSlotId]: {
        id: rootSlotId,
        nodeId: rootId,
        name: 'children',
        children: [textNodeId],
      },
    },
    themeRef: { type: 'inherit' },
  };

  const registry = testRegistry();
  const engine = new EditorEngine(doc, registry);
  const inspector = new EpistolaInspector();
  inspector.engine = engine;
  inspector.doc = engine.doc;
  inspector.selectedNodeId = textNodeId;

  return { engine, inspector, textNodeId, registry };
}

describe('EpistolaInspector generic presentation hook', () => {
  it('renders the default component label and full generic sections when no presentation hook is provided', () => {
    const { inspector } = setupInspector();

    const html = templateToHtml(inspector.render());

    // Default `text` component label.
    expect(html).toContain('Text');
    // Style preset and styles sections render because `applicableStyles: 'all'`.
    expect(html).toContain('Style Preset');
    expect(html).toContain('Styles');
    // Delete section renders by default.
    expect(html).toContain('Delete Block');
  });

  it('uses presentation.label and hides suppressed sections', () => {
    const { inspector, registry } = setupInspector();

    const original = registry.getOrThrow('text');
    const overridden: ComponentDefinition = {
      ...original,
      getInspectorPresentation: () => ({
        label: 'Custom Label',
        suppressPropsSection: true,
        suppressStylePresetSection: true,
        suppressStylesSection: true,
        suppressDeleteSection: true,
      }),
    };
    registry.register(overridden);

    const html = templateToHtml(inspector.render());

    expect(html).toContain('Custom Label');
    expect(html).not.toContain('Style Preset');
    expect(html).not.toContain('Delete Block');
    // The component-specific renderInspector still runs (text has none, so
    // nothing extra here), and the inspector header (label + id) still shows.
  });

  it('falls back to def.getLabel when presentation does not provide a label', () => {
    const { inspector, registry } = setupInspector();

    const original = registry.getOrThrow('text');
    const overridden: ComponentDefinition = {
      ...original,
      getLabel: () => 'Dynamic Label',
      // Suppression without label override.
      getInspectorPresentation: () => ({ suppressDeleteSection: true }),
    };
    registry.register(overridden);

    const html = templateToHtml(inspector.render());

    expect(html).toContain('Dynamic Label');
    expect(html).not.toContain('Delete Block');
    // Other sections still render because they were not suppressed.
    expect(html).toContain('Style Preset');
  });

  it('re-renders on any component-state change via the generic subscription', () => {
    const { engine, inspector } = setupInspector();

    // The subscription is wired in Lit's `updated` lifecycle, which only fires
    // when properties change through Lit's reactivity. In tests we set
    // properties directly, so call the private hook explicitly.
    (
      inspector as unknown as { _resubscribeComponentState: () => void }
    )._resubscribeComponentState();

    let updateCount = 0;
    (inspector as unknown as { requestUpdate: () => void }).requestUpdate = () => {
      updateCount++;
    };

    engine.setComponentState('arbitrary:key', { foo: 'bar' });
    engine.setComponentState('another:key', null);

    expect(updateCount).toBe(2);
  });
});
