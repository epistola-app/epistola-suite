/**
 * ComponentRegistry — defines what node types exist and their constraints.
 *
 * Used by: command validation, block palette, inspector, DnD.
 */

import type { TemplateDocument, NodeId, SlotId, Node, Slot } from '../types/index.js';
import type { DocumentIndexes } from './indexes.js';
import type { CommandResult } from './commands.js';
import type { FieldPath } from './schema-paths.js';
import { nanoid } from 'nanoid';
import { createTableDefinition } from '../components/table/table-registration.js';
import { createColumnsDefinition } from '../components/columns/columns-registration.js';
import { createDatatableDefinition } from '../components/datatable/datatable-registration.js';
import { createDatatableColumnDefinition } from '../components/datatable/datatable-column-registration.js';
import { createQrCodeDefinition } from '../components/qrcode/qrcode-registration.js';
import { buildIterationScope } from './scoped-fields.js';

// ---------------------------------------------------------------------------
// Component type constants
// ---------------------------------------------------------------------------

export const PAGE_HEADER_TYPE = 'pageheader';
export const PAGE_FOOTER_TYPE = 'pagefooter';

/** Check whether a component type is an anchored page block (header or footer). */
export function isAnchoredPageBlock(type: string): boolean {
  return type === PAGE_HEADER_TYPE || type === PAGE_FOOTER_TYPE;
}

// ---------------------------------------------------------------------------
// Component definition
// ---------------------------------------------------------------------------

export type ComponentCategory = 'content' | 'layout' | 'logic' | 'page';

export type AllowedChildren =
  | { mode: 'all' }
  | { mode: 'allowlist'; types: string[] }
  | { mode: 'denylist'; types: string[] }
  | { mode: 'none' };

export interface SlotTemplate {
  name: string;
  dynamic?: boolean;
}

export interface InspectorField {
  key: string;
  label: string;
  type: 'text' | 'number' | 'boolean' | 'select' | 'expression' | 'json' | 'color' | 'unit';
  options?: { label: string; value: unknown }[];
  defaultValue?: unknown;
  /** Available units for type 'unit' (e.g., ['pt', 'sp', '%']). */
  units?: string[];
}

/**
 * A copy-pasteable usage example for a component type. The fragment is a
 * partial TemplateDocument — backend consumers (e.g. the MCP server) can
 * surface it to AI clients designing templates.
 *
 * The fragment's `nodes` and `slots` maps can be merged into a real
 * TemplateDocument and `rootNodeId` referenced as the insertion handle.
 */
export interface ComponentExample {
  /** Stable identifier within the component, e.g. "minimal", "with-expression". */
  name: string;
  /** One-line description of what this example demonstrates. */
  description: string;
  fragment: ComponentExampleFragment;
}

export interface ComponentExampleFragment {
  /** Node id where the example starts (typically the component instance itself). */
  rootNodeId: NodeId;
  /** All nodes referenced by this fragment, including descendants. */
  nodes: Record<NodeId, Node>;
  /** All slots referenced by nodes in this fragment. */
  slots: Record<SlotId, Slot>;
}

/** Context passed to a scope provider for resolving scoped variables. */
export interface ScopeProviderContext {
  /** Schema-derived field paths for resolving array item types. */
  schemaFieldPaths: FieldPath[];
  /** Accumulated evaluation context from parent scopes (for resolving preview values). */
  evaluationContext?: Record<string, unknown>;
}

/** Variables and preview data a component introduces for its descendants. */
export interface ScopeDeclaration {
  /** Scoped FieldPath entries this component introduces. */
  variables: FieldPath[];
  /** Preview data to inject into the evaluation context (e.g., first array item). */
  evaluationData?: Record<string, unknown>;
}

/**
 * Lets a component customise how the generic inspector renders for the current
 * node — without the inspector needing any knowledge of the component's type
 * or internal state. All fields are optional; `undefined` means "use defaults".
 */
export interface InspectorPresentation {
  /** Override the node label rendered in the inspector header. */
  label?: string;
  /** Hide the generic Props section. */
  suppressPropsSection?: boolean;
  /** Hide the generic Style Preset section. */
  suppressStylePresetSection?: boolean;
  /** Hide the generic Styles section. */
  suppressStylesSection?: boolean;
  /** Hide the generic Delete section. */
  suppressDeleteSection?: boolean;
}

export interface ComponentDefinition {
  type: string;
  label: string;
  /** Dynamic label based on node state. If defined, takes precedence over static `label`. */
  getLabel?: (node: Node, engine: unknown) => string;
  icon?: string;
  category: ComponentCategory;
  /** Hide from the block palette (e.g. child-only components like datatable-column). */
  hidden?: boolean;
  slots: SlotTemplate[];
  allowedChildren: AllowedChildren;
  /**
   * Which style property keys this component supports.
   * - `'all'` = every property in the style registry
   * - `string[]` = explicit allowlist of property keys (empty = no styles)
   */
  applicableStyles: 'all' | string[];
  inspector: InspectorField[];
  /** Default styles for this component type (lowest priority in the cascade). */
  defaultStyles?: Record<string, unknown>;
  defaultProps?: Record<string, unknown>;
  /**
   * Optional hook to create initial slots for a node of this type.
   * Used for components (like columns, tables) whose slot count is derived
   * from props at creation time rather than from static slot templates.
   *
   * @param nodeId — the ID of the node being created
   * @param props — merged props (defaultProps overridden by any overrideProps)
   */
  createInitialSlots?: (nodeId: NodeId, props?: Record<string, unknown>) => Slot[];

  /**
   * Optional hook for creating a subtree of child nodes at insertion time.
   * Used by components (like datatable) that need atomic creation of the
   * parent node plus child nodes (columns) and all their slots.
   *
   * When present, this is called instead of createInitialSlots.
   *
   * @param nodeId — the ID of the parent node being created
   * @param props — merged props (defaultProps overridden by any overrideProps)
   */
  createSubtree?: (
    nodeId: NodeId,
    props?: Record<string, unknown>,
  ) => {
    slots: Slot[]; // this node's own slots (with children populated)
    extraNodes: Node[]; // descendant nodes (e.g. column nodes)
    extraSlots: Slot[]; // descendant slots (e.g. column body slots)
  };

  // ---------------------------------------------------------------------------
  // Extension hooks — let components customise UI without leaking into generics
  // ---------------------------------------------------------------------------

  /** Custom canvas rendering for complex layout components (e.g. columns, tables). */
  renderCanvas?: (ctx: {
    node: Node;
    doc: TemplateDocument;
    engine: unknown; // EditorEngine (typed as unknown to avoid circular imports)
    renderSlot: (slotId: SlotId) => unknown;
    selectedNodeId: NodeId | null;
  }) => unknown;

  /** Custom inspector section rendered above generic props. */
  renderInspector?: (ctx: { node: Node; engine: unknown }) => unknown;

  /**
   * Lets a component customise the generic inspector's presentation (label
   * override, hide standard sections) based on the current node and engine
   * state. Return `undefined` to use the defaults.
   */
  getInspectorPresentation?: (node: Node, engine: unknown) => InspectorPresentation | undefined;

  /** Called before dispatching prop changes. Can transform props (e.g. lock aspect ratio). */
  onPropChange?: (
    key: string,
    value: unknown,
    currentProps: Record<string, unknown>,
  ) => Record<string, unknown>;

  /** Pre-insert hook for palette (e.g. open a dialog). Returns override props or null to cancel. */
  onBeforeInsert?: (engine: unknown) => Promise<Record<string, unknown> | null>;

  /** Command type strings handled by this component's commandHandler. */
  commandTypes?: string[];
  /** Handler for component-specific commands. Returned inverse must use the same type strings. */
  commandHandler?: (
    doc: TemplateDocument,
    indexes: DocumentIndexes,
    command: unknown,
  ) => CommandResult;

  /** Optional singleton-style guard (e.g. allow only one page header per document). */
  maxInstancesPerDocument?: number;

  /**
   * Hand-curated usage examples surfaced by backend tools (e.g. the MCP
   * server's `list_component_types`). Each example is a self-contained
   * TemplateDocument fragment showing one realistic way the component is
   * used in practice. Drawn for inspiration from the demo catalog at
   * `modules/epistola-core/src/main/resources/demo/catalog/`.
   */
  examples?: ComponentExample[];

  /**
   * Declare scoped variables this component introduces for its descendants.
   * Called with the node's current state and a context for resolving types/values.
   * Returns null if the component doesn't introduce scope in its current state.
   */
  scopeProvider?: (node: Node, context: ScopeProviderContext) => ScopeDeclaration | null;
}

// ---------------------------------------------------------------------------
// Registry
// ---------------------------------------------------------------------------

export class ComponentRegistry {
  private _definitions: Map<string, ComponentDefinition> = new Map();

  register(def: ComponentDefinition): void {
    this._definitions.set(def.type, def);
  }

  get(type: string): ComponentDefinition | undefined {
    return this._definitions.get(type);
  }

  getOrThrow(type: string): ComponentDefinition {
    const def = this._definitions.get(type);
    if (!def) throw new Error(`Unknown component type: ${type}`);
    return def;
  }

  has(type: string): boolean {
    return this._definitions.has(type);
  }

  all(): ComponentDefinition[] {
    return Array.from(this._definitions.values());
  }

  /**
   * Get components that can be inserted by the user.
   *
   * If a document is provided, this also applies per-document instance limits
   * (e.g. singleton page header/footer blocks).
   */
  insertable(doc?: TemplateDocument): ComponentDefinition[] {
    return this.all()
      .filter((d) => !d.hidden)
      .filter((d) => !doc || this.canInsertInDocument(d.type, doc));
  }

  /**
   * Single source of truth for per-document instance limits (e.g. singleton
   * page header/footer). All insertion paths — commands, palette, DnD, and
   * any future features like block duplication — should call this method
   * to check whether another instance of `type` is allowed.
   */
  canInsertInDocument(type: string, doc: TemplateDocument): boolean {
    const def = this._definitions.get(type);
    if (!def) return false;

    const limit = def.maxInstancesPerDocument;
    if (typeof limit !== 'number') {
      return true;
    }

    if (!Number.isInteger(limit) || limit < 1) {
      return false;
    }

    let count = 0;
    for (const node of Object.values(doc.nodes)) {
      if (node.type === type) {
        count += 1;
        if (count >= limit) {
          return false;
        }
      }
    }

    return true;
  }

  /**
   * Check whether a node of `childType` can be placed in a slot owned by
   * a node of `parentType`.
   */
  canContain(parentType: string, childType: string): boolean {
    const def = this._definitions.get(parentType);
    if (!def) return false;

    switch (def.allowedChildren.mode) {
      case 'all':
        return true;
      case 'none':
        return false;
      case 'allowlist':
        return def.allowedChildren.types.includes(childType);
      case 'denylist':
        return !def.allowedChildren.types.includes(childType);
    }
  }

  /**
   * Create a new node + its initial slots for a given component type.
   * Returns the node, its slots, and optionally extra descendant nodes/slots
   * (for components with subtrees like datatable).
   *
   * @param overrideProps — optional props to merge over defaultProps
   *   (e.g. table dialog can pass `{ rows: 4, columns: 3 }`)
   */
  createNode(
    type: string,
    overrideProps?: Record<string, unknown>,
  ): {
    node: Node;
    slots: Slot[];
    extraNodes?: Node[];
    extraSlots?: Slot[];
  } {
    const def = this.getOrThrow(type);
    const nodeId = nanoid() as NodeId;

    const mergedProps = overrideProps
      ? { ...(def.defaultProps ? structuredClone(def.defaultProps) : {}), ...overrideProps }
      : def.defaultProps
        ? structuredClone(def.defaultProps)
        : undefined;

    // If the component defines a subtree initializer, use it (takes priority)
    if (def.createSubtree) {
      const { slots, extraNodes, extraSlots } = def.createSubtree(nodeId, mergedProps);
      const slotIds = slots.map((s) => s.id);
      const node: Node = {
        id: nodeId,
        type,
        slots: slotIds,
        props: mergedProps,
      };
      return {
        node,
        slots: [...slots, ...extraSlots],
        extraNodes: extraNodes.length > 0 ? extraNodes : undefined,
        extraSlots: extraSlots.length > 0 ? extraSlots : undefined,
      };
    }

    // If the component defines a custom slot initializer, use it
    if (def.createInitialSlots) {
      const slots = def.createInitialSlots(nodeId, mergedProps);
      const slotIds = slots.map((s) => s.id);
      const node: Node = {
        id: nodeId,
        type,
        slots: slotIds,
        props: mergedProps,
      };
      return { node, slots };
    }

    const slots: Slot[] = [];
    const slotIds: SlotId[] = [];

    for (const template of def.slots) {
      if (template.dynamic) continue; // Dynamic slots are created on demand
      const slotId = nanoid() as SlotId;
      slotIds.push(slotId);
      slots.push({
        id: slotId,
        nodeId,
        name: template.name,
        children: [],
      });
    }

    const node: Node = {
      id: nodeId,
      type,
      slots: slotIds,
      props: mergedProps,
    };

    return { node, slots };
  }
}

// ---------------------------------------------------------------------------
// Built-in component definitions
// ---------------------------------------------------------------------------

/** Layout-oriented style properties (spacing + background + borders, no typography). */
const LAYOUT_STYLES = [
  'padding',
  'margin',
  'backgroundColor',
  'border',
  'borderRadius',
  'keepTogether',
  'keepWithNext',
];

export function createDefaultRegistry(): ComponentRegistry {
  const registry = new ComponentRegistry();

  registry.register({
    type: 'root',
    label: 'Document Root',
    icon: 'file-text',
    category: 'layout',
    slots: [{ name: 'children' }],
    allowedChildren: { mode: 'denylist', types: ['root'] },
    applicableStyles: [],
    inspector: [],
  });

  registry.register({
    type: 'text',
    label: 'Text',
    icon: 'type',
    category: 'content',
    slots: [],
    allowedChildren: { mode: 'none' },
    applicableStyles: 'all',
    inspector: [],
    defaultStyles: { marginBottom: '1.5sp' },
    defaultProps: { content: null },
    examples: [
      {
        name: 'minimal',
        description: 'Plain paragraph with literal text content.',
        fragment: {
          rootNodeId: 'n-text-minimal',
          nodes: {
            'n-text-minimal': {
              id: 'n-text-minimal',
              type: 'text',
              slots: [],
              props: {
                content: {
                  type: 'doc',
                  content: [
                    {
                      type: 'paragraph',
                      content: [{ type: 'text', text: 'Hello, world!' }],
                    },
                  ],
                },
              },
            },
          },
          slots: {},
        },
      },
      {
        name: 'with-expression',
        description:
          'Text with an inline data expression — references a field on the input data via {{ ... }}.',
        fragment: {
          rootNodeId: 'n-text-with-expression',
          nodes: {
            'n-text-with-expression': {
              id: 'n-text-with-expression',
              type: 'text',
              slots: [],
              props: {
                content: {
                  type: 'doc',
                  content: [
                    {
                      type: 'paragraph',
                      content: [
                        { type: 'text', text: 'Dear ' },
                        { type: 'expression', attrs: { expression: 'recipient.name' } },
                        { type: 'text', text: ',' },
                      ],
                    },
                  ],
                },
              },
            },
          },
          slots: {},
        },
      },
      {
        name: 'heading',
        description: 'A level-1 heading. Use the heading node type within content for h1..h6.',
        fragment: {
          rootNodeId: 'n-text-heading',
          nodes: {
            'n-text-heading': {
              id: 'n-text-heading',
              type: 'text',
              slots: [],
              props: {
                content: {
                  type: 'doc',
                  content: [
                    {
                      type: 'heading',
                      attrs: { level: 1 },
                      content: [{ type: 'text', text: 'Invoice' }],
                    },
                  ],
                },
              },
            },
          },
          slots: {},
        },
      },
    ],
  });

  registry.register({
    type: 'container',
    label: 'Container',
    icon: 'box',
    category: 'layout',
    slots: [{ name: 'children' }],
    allowedChildren: { mode: 'all' },
    applicableStyles: 'all',
    inspector: [],
    defaultStyles: { marginBottom: '1.5sp' },
    examples: [
      {
        name: 'with-children',
        description:
          'Container holding a heading followed by a paragraph — typical for grouping a section.',
        fragment: {
          rootNodeId: 'n-container-with-children',
          nodes: {
            'n-container-with-children': {
              id: 'n-container-with-children',
              type: 'container',
              slots: ['s-container-with-children'],
            },
            'n-container-with-children-heading': {
              id: 'n-container-with-children-heading',
              type: 'text',
              slots: [],
              props: {
                content: {
                  type: 'doc',
                  content: [
                    {
                      type: 'heading',
                      attrs: { level: 2 },
                      content: [{ type: 'text', text: 'Section title' }],
                    },
                  ],
                },
              },
            },
            'n-container-with-children-body': {
              id: 'n-container-with-children-body',
              type: 'text',
              slots: [],
              props: {
                content: {
                  type: 'doc',
                  content: [
                    {
                      type: 'paragraph',
                      content: [{ type: 'text', text: 'Section body content goes here.' }],
                    },
                  ],
                },
              },
            },
          },
          slots: {
            's-container-with-children': {
              id: 's-container-with-children',
              nodeId: 'n-container-with-children',
              name: 'children',
              children: ['n-container-with-children-heading', 'n-container-with-children-body'],
            },
          },
        },
      },
      {
        name: 'styled-box',
        description: 'Container used purely as a styled box — bordered + padded with no children.',
        fragment: {
          rootNodeId: 'n-container-styled',
          nodes: {
            'n-container-styled': {
              id: 'n-container-styled',
              type: 'container',
              slots: ['s-container-styled-children'],
              styles: {
                border: '1pt solid #cbd5e1',
                padding: '8pt',
                borderRadius: '4pt',
              },
            },
          },
          slots: {
            's-container-styled-children': {
              id: 's-container-styled-children',
              nodeId: 'n-container-styled',
              name: 'children',
              children: [],
            },
          },
        },
      },
    ],
  });

  registry.register(createColumnsDefinition());
  registry.register(createTableDefinition());
  registry.register(createDatatableDefinition());
  registry.register(createDatatableColumnDefinition());
  registry.register(createQrCodeDefinition());

  registry.register({
    type: 'conditional',
    label: 'Conditional',
    icon: 'git-branch',
    category: 'logic',
    slots: [{ name: 'body' }],
    allowedChildren: { mode: 'all' },
    applicableStyles: LAYOUT_STYLES,
    inspector: [
      { key: 'condition.raw', label: 'Condition', type: 'expression' },
      { key: 'inverse', label: 'Inverse (else)', type: 'boolean', defaultValue: false },
    ],
    defaultProps: {
      condition: { raw: '', language: 'jsonata' },
      inverse: false,
    },
    examples: [
      {
        name: 'show-when-present',
        description:
          'Render the body only when the data field exists. Uses the JSONata $exists() helper.',
        fragment: {
          rootNodeId: 'n-cond-show',
          nodes: {
            'n-cond-show': {
              id: 'n-cond-show',
              type: 'conditional',
              slots: ['s-cond-show-body'],
              props: {
                condition: { raw: '$exists(notes)', language: 'jsonata' },
                inverse: false,
              },
            },
            'n-cond-show-text': {
              id: 'n-cond-show-text',
              type: 'text',
              slots: [],
              props: {
                content: {
                  type: 'doc',
                  content: [
                    {
                      type: 'paragraph',
                      content: [
                        { type: 'text', text: 'Notes: ' },
                        { type: 'expression', attrs: { expression: 'notes' } },
                      ],
                    },
                  ],
                },
              },
            },
          },
          slots: {
            's-cond-show-body': {
              id: 's-cond-show-body',
              nodeId: 'n-cond-show',
              name: 'body',
              children: ['n-cond-show-text'],
            },
          },
        },
      },
      {
        name: 'inverse-else',
        description:
          'Inverted condition — body renders only when the expression is FALSEY. Useful as the "else" branch.',
        fragment: {
          rootNodeId: 'n-cond-else',
          nodes: {
            'n-cond-else': {
              id: 'n-cond-else',
              type: 'conditional',
              slots: ['s-cond-else-body'],
              props: {
                condition: { raw: 'paid', language: 'jsonata' },
                inverse: true,
              },
            },
            'n-cond-else-text': {
              id: 'n-cond-else-text',
              type: 'text',
              slots: [],
              props: {
                content: {
                  type: 'doc',
                  content: [
                    {
                      type: 'paragraph',
                      content: [{ type: 'text', text: 'Payment is due.' }],
                    },
                  ],
                },
              },
            },
          },
          slots: {
            's-cond-else-body': {
              id: 's-cond-else-body',
              nodeId: 'n-cond-else',
              name: 'body',
              children: ['n-cond-else-text'],
            },
          },
        },
      },
    ],
  });

  registry.register({
    type: 'loop',
    label: 'Loop',
    icon: 'repeat',
    category: 'logic',
    slots: [{ name: 'body' }],
    allowedChildren: { mode: 'all' },
    applicableStyles: LAYOUT_STYLES,
    inspector: [
      { key: 'expression.raw', label: 'Expression', type: 'expression' },
      { key: 'itemAlias', label: 'Item Alias', type: 'text', defaultValue: 'item' },
      { key: 'indexAlias', label: 'Index Alias', type: 'text' },
    ],
    defaultProps: {
      expression: { raw: '', language: 'jsonata' },
      itemAlias: 'item',
      indexAlias: undefined,
    },
    scopeProvider: buildIterationScope,
    examples: [
      {
        name: 'over-array',
        description:
          'Iterate over an array on the input data. Each iteration introduces "item" as a scoped variable referenceable inside the body slot.',
        fragment: {
          rootNodeId: 'n-loop-items',
          nodes: {
            'n-loop-items': {
              id: 'n-loop-items',
              type: 'loop',
              slots: ['s-loop-items-body'],
              props: {
                expression: { raw: 'attendees', language: 'jsonata' },
                itemAlias: 'item',
              },
            },
            'n-loop-items-line': {
              id: 'n-loop-items-line',
              type: 'text',
              slots: [],
              props: {
                content: {
                  type: 'doc',
                  content: [
                    {
                      type: 'paragraph',
                      content: [
                        { type: 'expression', attrs: { expression: 'item.name' } },
                        { type: 'text', text: ' — ' },
                        { type: 'expression', attrs: { expression: 'item.email' } },
                      ],
                    },
                  ],
                },
              },
            },
          },
          slots: {
            's-loop-items-body': {
              id: 's-loop-items-body',
              nodeId: 'n-loop-items',
              name: 'body',
              children: ['n-loop-items-line'],
            },
          },
        },
      },
    ],
  });

  registry.register({
    type: 'datalist',
    label: 'Data List',
    icon: 'list',
    category: 'logic',
    slots: [{ name: 'item-template' }],
    allowedChildren: { mode: 'all' },
    applicableStyles: LAYOUT_STYLES,
    inspector: [
      { key: 'expression.raw', label: 'Data Source', type: 'expression' },
      { key: 'itemAlias', label: 'Item Variable', type: 'text', defaultValue: 'item' },
      { key: 'indexAlias', label: 'Index Variable', type: 'text' },
      {
        key: 'listType',
        label: 'List Type',
        type: 'select',
        options: [
          { label: 'Bullet', value: 'bullet' },
          { label: 'Numbered', value: 'decimal' },
          { label: 'Letters (a, b, c)', value: 'lower-alpha' },
          { label: 'Letters (A, B, C)', value: 'upper-alpha' },
          { label: 'Roman (i, ii, iii)', value: 'lower-roman' },
          { label: 'Roman (I, II, III)', value: 'upper-roman' },
          { label: 'No marker', value: 'none' },
        ],
      },
    ],
    defaultProps: {
      expression: { raw: '', language: 'jsonata' },
      itemAlias: 'item',
      indexAlias: undefined,
      listType: 'bullet',
    },
    scopeProvider: buildIterationScope,
    examples: [
      {
        name: 'bulleted-list',
        description:
          'Render an array as a bulleted list. The item-template slot holds one example item; the list expands at render time.',
        fragment: {
          rootNodeId: 'n-datalist-bullet',
          nodes: {
            'n-datalist-bullet': {
              id: 'n-datalist-bullet',
              type: 'datalist',
              slots: ['s-datalist-bullet-template'],
              props: {
                expression: { raw: 'features', language: 'jsonata' },
                itemAlias: 'item',
                listType: 'bullet',
              },
            },
            'n-datalist-bullet-item': {
              id: 'n-datalist-bullet-item',
              type: 'text',
              slots: [],
              props: {
                content: {
                  type: 'doc',
                  content: [
                    {
                      type: 'paragraph',
                      content: [{ type: 'expression', attrs: { expression: 'item' } }],
                    },
                  ],
                },
              },
            },
          },
          slots: {
            's-datalist-bullet-template': {
              id: 's-datalist-bullet-template',
              nodeId: 'n-datalist-bullet',
              name: 'item-template',
              children: ['n-datalist-bullet-item'],
            },
          },
        },
      },
      {
        name: 'numbered-list',
        description:
          'Same as bulleted-list but using a decimal (1., 2., 3.) marker via listType="decimal".',
        fragment: {
          rootNodeId: 'n-datalist-numbered',
          nodes: {
            'n-datalist-numbered': {
              id: 'n-datalist-numbered',
              type: 'datalist',
              slots: ['s-datalist-numbered-template'],
              props: {
                expression: { raw: 'steps', language: 'jsonata' },
                itemAlias: 'step',
                listType: 'decimal',
              },
            },
            'n-datalist-numbered-item': {
              id: 'n-datalist-numbered-item',
              type: 'text',
              slots: [],
              props: {
                content: {
                  type: 'doc',
                  content: [
                    {
                      type: 'paragraph',
                      content: [{ type: 'expression', attrs: { expression: 'step.title' } }],
                    },
                  ],
                },
              },
            },
          },
          slots: {
            's-datalist-numbered-template': {
              id: 's-datalist-numbered-template',
              nodeId: 'n-datalist-numbered',
              name: 'item-template',
              children: ['n-datalist-numbered-item'],
            },
          },
        },
      },
    ],
  });

  registry.register({
    type: 'separator',
    label: 'Separator',
    icon: 'minus',
    category: 'content',
    slots: [],
    allowedChildren: { mode: 'none' },
    applicableStyles: ['margin'],
    inspector: [
      { key: 'thickness', label: 'Thickness', type: 'unit', units: ['pt'], defaultValue: '1pt' },
      {
        key: 'width',
        label: 'Width',
        type: 'unit',
        units: ['%'],
        defaultValue: '100%',
      },
      { key: 'color', label: 'Color', type: 'color' },
      {
        key: 'style',
        label: 'Style',
        type: 'select',
        options: [
          { label: 'Solid', value: 'solid' },
          { label: 'Dashed', value: 'dashed' },
          { label: 'Dotted', value: 'dotted' },
        ],
      },
    ],
    defaultStyles: {
      marginTop: '1.5sp',
      marginBottom: '1.5sp',
    },
    defaultProps: {
      thickness: '1pt',
      width: '100%',
      color: '#d1d5db',
      style: 'solid',
    },
    examples: [
      {
        name: 'thin-line',
        description: 'A 1pt solid horizontal rule, full-width — the typical visual section break.',
        fragment: {
          rootNodeId: 'n-separator-thin',
          nodes: {
            'n-separator-thin': {
              id: 'n-separator-thin',
              type: 'separator',
              slots: [],
              props: {
                thickness: '1pt',
                width: '100%',
                color: '#d1d5db',
                style: 'solid',
              },
            },
          },
          slots: {},
        },
      },
    ],
  });

  registry.register({
    type: 'addressblock',
    label: 'Address Block',
    icon: 'mail',
    category: 'page',
    slots: [{ name: 'address' }, { name: 'aside' }],
    allowedChildren: { mode: 'all' },
    applicableStyles: [],
    inspector: [
      {
        key: 'standard',
        label: 'Envelope Preset',
        type: 'select',
        options: [
          { label: 'DIN C5/6 Left Window', value: 'din-c56-left' },
          { label: 'DIN C5/6 Right Window', value: 'din-c56-right' },
          { label: 'Custom', value: 'custom' },
        ],
      },
      {
        key: 'align',
        label: 'Align',
        type: 'select',
        options: [
          { label: 'Left', value: 'left' },
          { label: 'Right', value: 'right' },
        ],
      },
      { key: 'top', label: 'Top (mm)', type: 'number' },
      { key: 'sideDistance', label: 'Side Distance (mm)', type: 'number' },
      { key: 'addressWidth', label: 'Address Width (mm)', type: 'number' },
      { key: 'height', label: 'Height (mm)', type: 'number' },
    ],
    defaultProps: {
      standard: 'din-c56-left',
      align: 'left',
      top: 45,
      sideDistance: 20,
      addressWidth: 85,
      height: 45,
    },
    onPropChange: (key, value, props) => {
      if (key === 'standard' && value !== 'custom') {
        const presets: Record<string, Record<string, unknown>> = {
          'din-c56-left': {
            align: 'left',
            top: 45,
            sideDistance: 20,
            addressWidth: 85,
            height: 45,
          },
          'din-c56-right': {
            align: 'right',
            top: 45,
            sideDistance: 20,
            addressWidth: 85,
            height: 45,
          },
        };
        const preset = presets[value as string];
        if (preset) Object.assign(props, preset);
      }
      return props;
    },
    maxInstancesPerDocument: 1,
    examples: [
      {
        name: 'din-c56-left',
        description:
          'Standard left-window envelope address block (DIN C5/C6) with the recipient address rendered from data fields. Singleton: only one address block per document is allowed.',
        fragment: {
          rootNodeId: 'n-address-block',
          nodes: {
            'n-address-block': {
              id: 'n-address-block',
              type: 'addressblock',
              slots: ['s-address-block-address', 's-address-block-aside'],
              props: {
                standard: 'din-c56-left',
                align: 'left',
                top: 45,
                sideDistance: 20,
                addressWidth: 85,
                height: 45,
              },
            },
            'n-address-block-text': {
              id: 'n-address-block-text',
              type: 'text',
              slots: [],
              props: {
                content: {
                  type: 'doc',
                  content: [
                    {
                      type: 'paragraph',
                      content: [
                        { type: 'expression', attrs: { expression: 'recipient.name' } },
                        { type: 'hard_break' },
                        { type: 'expression', attrs: { expression: 'recipient.address' } },
                        { type: 'hard_break' },
                        { type: 'expression', attrs: { expression: 'recipient.city' } },
                      ],
                    },
                  ],
                },
              },
            },
          },
          slots: {
            's-address-block-address': {
              id: 's-address-block-address',
              nodeId: 'n-address-block',
              name: 'address',
              children: ['n-address-block-text'],
            },
            's-address-block-aside': {
              id: 's-address-block-aside',
              nodeId: 'n-address-block',
              name: 'aside',
              children: [],
            },
          },
        },
      },
    ],
  });

  registry.register({
    type: 'pagebreak',
    label: 'Page Break',
    icon: 'file-break',
    category: 'page',
    slots: [],
    allowedChildren: { mode: 'none' },
    applicableStyles: [],
    inspector: [],
    examples: [
      {
        name: 'minimal',
        description: 'A hard page break — forces a new page in the rendered document.',
        fragment: {
          rootNodeId: 'n-pagebreak',
          nodes: {
            'n-pagebreak': {
              id: 'n-pagebreak',
              type: 'pagebreak',
              slots: [],
            },
          },
          slots: {},
        },
      },
    ],
  });

  registry.register({
    type: PAGE_HEADER_TYPE,
    label: 'Page Header',
    icon: 'panel-top',
    category: 'page',
    slots: [{ name: 'children' }],
    allowedChildren: { mode: 'all' },
    applicableStyles: 'all',
    inspector: [
      { key: 'height', label: 'Height', type: 'unit', units: ['pt', 'sp'], defaultValue: '60pt' },
      { key: 'hideOnFirstPage', label: 'Hide on first page', type: 'boolean' },
    ],
    maxInstancesPerDocument: 1,
    examples: [
      {
        name: 'title-only',
        description:
          'Minimal page header with a single text block. Anchored to the top of every page (or every page except the first when hideOnFirstPage is set).',
        fragment: {
          rootNodeId: 'n-pageheader',
          nodes: {
            'n-pageheader': {
              id: 'n-pageheader',
              type: 'pageheader',
              slots: ['s-pageheader-children'],
              props: { height: '60pt', hideOnFirstPage: false },
            },
            'n-pageheader-text': {
              id: 'n-pageheader-text',
              type: 'text',
              slots: [],
              props: {
                content: {
                  type: 'doc',
                  content: [
                    {
                      type: 'paragraph',
                      content: [{ type: 'expression', attrs: { expression: 'tenant.name' } }],
                    },
                  ],
                },
              },
            },
          },
          slots: {
            's-pageheader-children': {
              id: 's-pageheader-children',
              nodeId: 'n-pageheader',
              name: 'children',
              children: ['n-pageheader-text'],
            },
          },
        },
      },
    ],
  });

  registry.register({
    type: PAGE_FOOTER_TYPE,
    label: 'Page Footer',
    icon: 'panel-bottom',
    category: 'page',
    slots: [{ name: 'children' }],
    allowedChildren: { mode: 'all' },
    applicableStyles: 'all',
    inspector: [
      { key: 'height', label: 'Height', type: 'unit', units: ['pt', 'sp'], defaultValue: '60pt' },
      { key: 'hideOnFirstPage', label: 'Hide on first page', type: 'boolean' },
    ],
    maxInstancesPerDocument: 1,
    examples: [
      {
        name: 'centered-text',
        description:
          'Page footer with a single centered text block. Anchored to the bottom of every page.',
        fragment: {
          rootNodeId: 'n-pagefooter',
          nodes: {
            'n-pagefooter': {
              id: 'n-pagefooter',
              type: 'pagefooter',
              slots: ['s-pagefooter-children'],
              props: { height: '40pt', hideOnFirstPage: false },
            },
            'n-pagefooter-text': {
              id: 'n-pagefooter-text',
              type: 'text',
              slots: [],
              styles: { textAlign: 'center', fontSize: '8pt' },
              props: {
                content: {
                  type: 'doc',
                  content: [
                    {
                      type: 'paragraph',
                      content: [{ type: 'text', text: 'Confidential — for internal use only' }],
                    },
                  ],
                },
              },
            },
          },
          slots: {
            's-pagefooter-children': {
              id: 's-pagefooter-children',
              nodeId: 'n-pagefooter',
              name: 'children',
              children: ['n-pagefooter-text'],
            },
          },
        },
      },
    ],
  });

  return registry;
}
