import { LitElement, html, nothing } from "lit";
import { customElement, property } from "lit/decorators.js";
import { styleMap } from "lit/directives/style-map.js";
import {
  draggable,
  dropTargetForElements,
  monitorForElements,
} from "@atlaskit/pragmatic-drag-and-drop/element/adapter";
import {
  attachInstruction as attachListItemInstruction,
  extractInstruction as extractListItemInstruction,
  type Instruction as ListItemInstruction,
} from "@atlaskit/pragmatic-drag-and-drop-hitbox/list-item";
import type { TemplateDocument, NodeId, SlotId } from "../types/index.js";
import type { EditorEngine } from "../engine/EditorEngine.js";
import { isDragData, isBlockDrag, type DragData } from "../dnd/types.js";
import {
  resolveDropOnBlockEdge,
  canDropHere,
  type DropLocation,
  type Edge,
} from "../dnd/drop-logic.js";
import { handleDrop } from "../dnd/drop-handler.js";
import { setEditorDragPreview } from "../dnd/native-drag-preview.js";
import { icon } from "./icons.js";
import "../ui/EpistolaTextEditor.js";

@customElement("epistola-canvas")
export class EpistolaCanvas extends LitElement {
  override createRenderRoot() {
    return this;
  }

  @property({ attribute: false }) engine?: EditorEngine;
  @property({ attribute: false }) doc?: TemplateDocument;
  @property({ attribute: false }) selectedNodeId: NodeId | null = null;

  private _dndCleanup: (() => void) | null = null;
  private _unsubComponentState?: () => void;
  private _subscribedEngine?: EditorEngine;
  private _insertMarkerByKey = new Map<string, HTMLElement>();
  private _activeInsertMarkerKey: string | null = null;

  private _handleSelect(e: Event, nodeId: NodeId) {
    e.stopPropagation();
    this.engine?.selectNode(nodeId);
    this._maybeFocusTextEditor(nodeId);
  }

  private _handleCanvasClick() {
    this.engine?.selectNode(null);
  }

  private _handleDeleteBlock(nodeId: NodeId) {
    if (!this.engine) return;
    this.engine.dispatch({ type: "RemoveNode", nodeId });
    this.engine.selectNode(null);
  }

  private _handleFocus(nodeId: NodeId) {
    this.engine?.selectNode(nodeId);
    this._maybeFocusTextEditor(nodeId);
  }

  private _maybeFocusTextEditor(nodeId: NodeId) {
    const doc = this.doc;
    if (!doc) return;
    const node = doc.nodes[nodeId];
    if (!node || node.type !== "text") return;

    requestAnimationFrame(() => {
      const blockEl = this.querySelector<HTMLElement>(
        `.canvas-block[data-node-id="${nodeId}"]`,
      );
      const textEditor = blockEl?.querySelector<HTMLElement>(
        "epistola-text-editor",
      );
      if (!textEditor) return;
      const focusEditor = (textEditor as { focusEditor?: () => void })
        .focusEditor;
      focusEditor?.call(textEditor);
    });
  }

  override updated() {
    this._dndCleanup?.();
    this._dndCleanup = this._setupDnD();

    // Subscribe to component state changes (e.g. table cell selection) so the
    // canvas re-renders when state is updated from within renderCanvas hooks.
    if (this.engine && this.engine !== this._subscribedEngine) {
      this._unsubComponentState?.();
      this._subscribedEngine = this.engine;
      this._unsubComponentState = this.engine.events.on(
        "component-state:change",
        () => {
          this.requestUpdate();
        },
      );
    }
  }

  override disconnectedCallback() {
    this._dndCleanup?.();
    this._dndCleanup = null;
    this._unsubComponentState?.();
    super.disconnectedCallback();
  }

  // ---------------------------------------------------------------------------
  // DnD setup
  // ---------------------------------------------------------------------------

  private _setupDnD(): (() => void) | null {
    if (!this.engine || !this.doc) return null;

    const cleanups: (() => void)[] = [];
    this._insertMarkerByKey.clear();
    this._clearActiveInsertMarker();

    // Setup drag sources on canvas blocks (skip root)
    const blocks = this.querySelectorAll<HTMLElement>(
      ".canvas-block[data-node-id]",
    );
    for (const blockEl of blocks) {
      const nodeId = blockEl.dataset.nodeId as NodeId | undefined;
      if (!nodeId || nodeId === this.doc.root) continue;

      const node = this.doc.nodes[nodeId];
      if (!node) continue;
      const isFixedPageBlock = node.type === "pageheader" || node.type === "pagefooter";
      const blockLabel = this.engine.registry.get(node.type)?.label ?? node.type;

      // Drag source
      if (!isFixedPageBlock) {
        cleanups.push(
          draggable({
            element: blockEl,
            dragHandle:
              blockEl.querySelector<HTMLElement>(".canvas-block-header") ??
              blockEl,
            getInitialData: (): DragData => ({
              source: "block",
              nodeId,
              blockType: node.type,
            }),
            onGenerateDragPreview: ({ nativeSetDragImage, location }) => {
              setEditorDragPreview({
                nativeSetDragImage,
                sourceElement: blockEl,
                input: location.current.input,
                label: blockLabel,
                intent: "move",
              });
            },
            onDragStart: () => blockEl.classList.add("dragging"),
            onDrop: () => blockEl.classList.remove("dragging"),
          }),
        );
      }

      // Drop target on each block (list-item instruction for before / after)
      cleanups.push(
        dropTargetForElements({
          element: blockEl,
          getIsSticky: () => true,
          getData: ({ input, element }) =>
            attachListItemInstruction(
              { nodeId },
              {
                element,
                input,
                axis: "vertical",
                operations: {
                  "reorder-before": "available",
                  "reorder-after": "available",
                  combine: "not-available",
                },
              },
            ),
          canDrop: ({ source }) => {
            const dragData = source.data as Record<string, unknown>;
            if (!isDragData(dragData)) return false;

            // Can't drop a block on itself
            if (isBlockDrag(dragData) && dragData.nodeId === nodeId)
              return false;

            // Resolve parent slot of this block via DOM
            const slotEl = blockEl.closest<HTMLElement>("[data-slot-id]");
            const parentSlotId = slotEl?.dataset.slotId as SlotId | undefined;
            if (!parentSlotId) return false;

            return canDropHere(
              dragData,
              parentSlotId,
              this.doc!,
              this.engine!.indexes,
              this.engine!.registry,
            );
          },
          onDragEnter: ({ self, source, location }) => {
            const dragData = source.data as Record<string, unknown>;
            if (!isDragData(dragData)) return;

            // Only handle if this block is the innermost drop target.
            if (location.current.dropTargets[0]?.element !== blockEl) return;

            const dropLocation = this._resolveDropLocationFromInstruction(
              nodeId,
              extractListItemInstruction(self.data),
            );
            if (!dropLocation) return;

            this._setActiveInsertMarker(
              dropLocation.targetSlotId,
              dropLocation.index,
              dragData,
            );
          },
          onDrag: ({ self, source, location }) => {
            const dragData = source.data as Record<string, unknown>;
            if (!isDragData(dragData)) return;

            if (location.current.dropTargets[0]?.element !== blockEl) return;

            const dropLocation = this._resolveDropLocationFromInstruction(
              nodeId,
              extractListItemInstruction(self.data),
            );
            if (!dropLocation) return;

            this._setActiveInsertMarker(
              dropLocation.targetSlotId,
              dropLocation.index,
              dragData,
            );
          },
          onDragLeave: ({ location }) => {
            if (location.current.dropTargets.length === 0) {
              this._clearActiveInsertMarker();
            }
          },
          onDrop: ({ self, source, location }) => {
            // If a deeper target (nested slot) is innermost, skip — it handles the drop
            if (location.current.dropTargets[0]?.element !== blockEl) return;

            const dragData = source.data as Record<string, unknown>;
            if (!isDragData(dragData)) return;

            const dropLocation = this._resolveDropLocationFromInstruction(
              nodeId,
              extractListItemInstruction(self.data),
            );
            if (!dropLocation) return;

            this._clearActiveInsertMarker();
            this._handleDrop(
              dragData,
              dropLocation.targetSlotId,
              dropLocation.index,
            );
          },
        }),
      );
    }

    // Setup drop targets on canonical insertion markers.
    // Markers are rendered for every slot index (before / between / after),
    // so there is exactly one visual destination for each insertion point.
    const insertMarkers = this.querySelectorAll<HTMLElement>(
      ".canvas-insert-marker[data-slot-id][data-insert-index]",
    );
    for (const markerEl of insertMarkers) {
      const slotId = markerEl.dataset.slotId as SlotId | undefined;
      if (!slotId) continue;

      const insertIndexRaw = markerEl.dataset.insertIndex;
      if (insertIndexRaw == null) continue;

      const insertIndex = Number.parseInt(insertIndexRaw, 10);
      if (!Number.isInteger(insertIndex) || insertIndex < 0) continue;

      this._insertMarkerByKey.set(
        this._insertMarkerKey(slotId, insertIndex),
        markerEl,
      );

      cleanups.push(
        dropTargetForElements({
          element: markerEl,
          getIsSticky: () => true,
          canDrop: ({ source }) => {
            const dragData = source.data as Record<string, unknown>;
            if (!isDragData(dragData)) return false;
            return canDropHere(
              dragData,
              slotId,
              this.doc!,
              this.engine!.indexes,
              this.engine!.registry,
            );
          },
          onDragEnter: ({ source, location }) => {
            const dragData = source.data as Record<string, unknown>;
            if (!isDragData(dragData)) return;
            if (location.current.dropTargets[0]?.element !== markerEl) return;

            this._setActiveInsertMarker(slotId, insertIndex, dragData);
          },
          onDrag: ({ source, location }) => {
            const dragData = source.data as Record<string, unknown>;
            if (!isDragData(dragData)) return;
            if (location.current.dropTargets[0]?.element !== markerEl) return;

            this._setActiveInsertMarker(slotId, insertIndex, dragData);
          },
          onDragLeave: ({ location }) => {
            if (location.current.dropTargets.length === 0) {
              this._clearActiveInsertMarker();
            }
          },
          onDrop: ({ source, location }) => {
            if (location.current.dropTargets[0]?.element !== markerEl) return;

            const dragData = source.data as Record<string, unknown>;
            if (!isDragData(dragData)) return;

            this._clearActiveInsertMarker();
            this._handleDrop(dragData, slotId, insertIndex);
          },
        }),
      );
    }

    cleanups.push(
      monitorForElements({
        canMonitor: ({ source }) => {
          const dragData = source.data as Record<string, unknown>;
          return isDragData(dragData);
        },
        onDrop: () => {
          this._clearActiveInsertMarker();
        },
      }),
    );

    return () => {
      this._clearActiveInsertMarker();
      this._insertMarkerByKey.clear();
      cleanups.forEach((fn) => fn());
    };
  }

  private _insertMarkerKey(slotId: SlotId, index: number): string {
    return `${slotId}:${String(index)}`;
  }

  private _toDropEdge(instruction: ListItemInstruction | null): Edge | null {
    if (!instruction) return null;
    if (instruction.operation === "reorder-before") return "top";
    if (instruction.operation === "reorder-after") return "bottom";
    return null;
  }

  private _resolveDropLocationFromInstruction(
    nodeId: NodeId,
    instruction: ListItemInstruction | null,
  ): DropLocation | null {
    const edge = this._toDropEdge(instruction);
    if (!edge) return null;

    return resolveDropOnBlockEdge(nodeId, edge, this.doc!, this.engine!.indexes);
  }

  private _setActiveInsertMarker(
    slotId: SlotId,
    index: number,
    dragData: DragData,
  ): void {
    const key = this._insertMarkerKey(slotId, index);
    const markerEl = this._insertMarkerByKey.get(key);
    if (!markerEl) return;

    const previewLabel = this._resolveDropPreviewLabel(dragData);

    if (this._activeInsertMarkerKey === key) {
      markerEl.setAttribute("data-drop-preview", previewLabel);
      return;
    }

    this._clearActiveInsertMarker();

    markerEl.classList.add("active");
    markerEl.setAttribute("data-drop-preview", previewLabel);
    this._activeInsertMarkerKey = key;
  }

  private _clearActiveInsertMarker(): void {
    if (!this._activeInsertMarkerKey) return;

    const markerEl = this._insertMarkerByKey.get(this._activeInsertMarkerKey);
    markerEl?.classList.remove("active");
    markerEl?.removeAttribute("data-drop-preview");

    this._activeInsertMarkerKey = null;
  }

  private _resolveDropPreviewLabel(dragData: DragData): string {
    const action = dragData.source === "palette" ? "Insert" : "Move";

    const blockType = dragData.source === "palette"
      ? dragData.blockType
      : this.doc?.nodes[dragData.nodeId]?.type ?? dragData.blockType;

    const label = this.engine?.registry.get(blockType)?.label ?? blockType;
    return `${action} ${label}`;
  }

  // ---------------------------------------------------------------------------
  // Drop handler
  // ---------------------------------------------------------------------------

  private _handleDrop(dragData: DragData, targetSlotId: SlotId, index: number) {
    if (!this.engine) return;
    handleDrop(this.engine, dragData, targetSlotId, index);
  }

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  override render() {
    if (!this.doc || !this.engine) {
      return html`<div class="editor-empty">No document</div>`;
    }

    const pageSettings = this.engine.resolvedPageSettings;
    const pageStyle: Record<string, string> = {};
    if (pageSettings.backgroundColor) {
      pageStyle.backgroundColor = pageSettings.backgroundColor;
    }

    return html`
      <div class="epistola-canvas" @click=${this._handleCanvasClick}>
        <div class="canvas-page" style=${styleMap(pageStyle)}>
          ${this._renderNodeChildren(this.doc.root)}
        </div>
      </div>
    `;
  }

  private _renderNodeChildren(nodeId: NodeId): unknown {
    const doc = this.doc!;
    const node = doc.nodes[nodeId];
    if (!node) return nothing;

    if (node.slots.length === 0) {
      // Leaf node
      return this._renderLeafNode(nodeId);
    }

    return html` ${node.slots.map((slotId) => this._renderSlot(slotId))} `;
  }

  private _renderSlot(slotId: SlotId): unknown {
    const doc = this.doc!;
    const slot = doc.slots[slotId];
    if (!slot) return nothing;

    const parentNode = doc.nodes[slot.nodeId];
    const isMultiSlot = parentNode && parentNode.slots.length > 1;
    const emptySlotHint = isMultiSlot ? slot.name : "Drop blocks here";

    return html`
      <div
        class="canvas-slot ${slot.children.length === 0 ? "empty" : ""}"
        data-slot-id=${slotId}
        data-slot-name=${slot.name}
      >
        ${slot.children.length === 0
          ? this._renderInsertMarker(slotId, 0, {
              hint: emptySlotHint,
              empty: true,
              tail: true,
            })
          : html`
              ${slot.children.map(
                (childId, index) => html`
                  ${this._renderInsertMarker(slotId, index)}
                  ${this._renderBlock(childId)}
                `,
              )}
              ${this._renderInsertMarker(slotId, slot.children.length, {
                tail: true,
              })}
            `}
      </div>
    `;
  }

  private _renderInsertMarker(
    slotId: SlotId,
    insertIndex: number,
    options?: { hint?: string; tail?: boolean; empty?: boolean },
  ): unknown {
    const markerClasses = ["canvas-insert-marker"];
    if (options?.tail) markerClasses.push("tail");
    if (options?.empty) markerClasses.push("empty");

    return html`
      <div
        class=${markerClasses.join(" ")}
        data-slot-id=${slotId}
        data-insert-index=${String(insertIndex)}
      >
        ${options?.hint
          ? html`<span class="canvas-slot-hint">${options.hint}</span>`
          : nothing}
      </div>
    `;
  }

  private _renderBlock(nodeId: NodeId): unknown {
    const doc = this.doc!;
    const node = doc.nodes[nodeId];
    if (!node) return nothing;

    const isSelected = this.selectedNodeId === nodeId;
    const def = this.engine!.registry.get(node.type);
    const label = def?.label ?? node.type;

    // Resolve styles through the full cascade, filtered by component's applicable styles
    const resolvedStyles = this.engine!.getResolvedNodeStyles(nodeId);
    const applicableStyles = def?.applicableStyles;
    const filteredStyles = filterByApplicableStyles(
      resolvedStyles,
      applicableStyles,
    );
    const contentStyle = toStyleMap(filteredStyles);

    return html`
      <div
        class="canvas-block ${isSelected ? "selected" : ""}"
        data-testid="canvas-block"
        data-node-id=${nodeId}
        tabindex="0"
        @click=${(e: Event) => this._handleSelect(e, nodeId)}
        @focus=${() => this._handleFocus(nodeId)}
      >
        <!-- Block header -->
        <div class="canvas-block-header">
          <span class="canvas-block-label">${label}</span>
          <span class="canvas-block-id">${nodeId.slice(0, 6)}</span>
          ${isSelected
            ? html`
                <button
                  class="canvas-block-delete"
                  title="Delete block"
                  @click=${(e: Event) => {
                    e.stopPropagation();
                    this._handleDeleteBlock(nodeId);
                  }}
                >
                  ${icon("trash-2", 14)}
                </button>
              `
            : nothing}
        </div>

        <!-- Block content area -->
        <div
          class="canvas-block-content ${node.type === "text"
            ? "text-type"
            : ""}"
          style=${styleMap(contentStyle)}
        >
          ${this._renderBlockContent(nodeId)}
        </div>
      </div>
    `;
  }

  private _renderBlockContent(nodeId: NodeId): unknown {
    const doc = this.doc!;
    const node = doc.nodes[nodeId];
    if (!node) return nothing;

    // Delegate to component's renderCanvas hook if present (checked first so
    // leaf components like image can provide custom rendering)
    const def = this.engine!.registry.get(node.type);
    if (def?.renderCanvas) {
      return def.renderCanvas({
        node,
        doc,
        engine: this.engine!,
        renderSlot: (slotId: SlotId) => this._renderSlot(slotId),
        selectedNodeId: this.selectedNodeId,
      });
    }

    // For leaf nodes with no slots, show a content placeholder
    if (node.slots.length === 0) {
      return this._renderLeafNode(nodeId);
    }

    // Default: render all slots
    return html` ${node.slots.map((slotId) => this._renderSlot(slotId))} `;
  }

  private _renderLeafNode(nodeId: NodeId): unknown {
    const doc = this.doc!;
    const node = doc.nodes[nodeId];
    if (!node) return nothing;

    switch (node.type) {
      case "text": {
        const resolvedStyles = this.engine!.getResolvedNodeStyles(nodeId);
        const def = this.engine!.registry.get(node.type);
        const applicableStyles = def?.applicableStyles;
        const filteredStyles = filterByApplicableStyles(
          resolvedStyles,
          applicableStyles,
        );
        const textStyles = toStyleMap(filteredStyles);
        return html`
          <epistola-text-editor
            .nodeId=${nodeId}
            .content=${node.props?.content ?? null}
            .resolvedStyles=${textStyles}
            .engine=${this.engine}
            .isSelected=${this.selectedNodeId === nodeId}
          ></epistola-text-editor>
        `;
      }
      case "pagebreak":
        return html`<div class="canvas-pagebreak">
          <div class="canvas-pagebreak-line"></div>
          <span class="canvas-pagebreak-label">Page Break</span>
          <div class="canvas-pagebreak-line"></div>
        </div>`;
      default:
        return html`<div class="canvas-leaf-default">${node.type}</div>`;
    }
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Filter a resolved styles object to only include properties the component supports.
 * Uses prefix matching: an allowlist entry like 'margin' matches 'marginTop', 'marginBottom', etc.
 */
function filterByApplicableStyles(
  styles: Record<string, unknown>,
  applicableStyles: "all" | string[] | undefined,
): Record<string, unknown> {
  if (!applicableStyles || applicableStyles === "all") return styles;
  if (applicableStyles.length === 0) return {};
  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(styles)) {
    if (applicableStyles.includes(key)) {
      result[key] = value;
    } else if (applicableStyles.some((allowed) => key.startsWith(allowed))) {
      result[key] = value;
    }
  }
  return result;
}

/** Convert a camelCase key to kebab-case CSS property name. */
function camelToKebab(key: string): string {
  return key.replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`);
}

/**
 * Convert a resolved styles object to a styleMap-compatible record.
 * All values are scalar strings (e.g. marginTop: '10px' → margin-top: 10px).
 */
function toStyleMap(styles: Record<string, unknown>): Record<string, string> {
  const result: Record<string, string> = {};
  for (const [key, value] of Object.entries(styles)) {
    if (value == null) continue;
    result[camelToKebab(key)] = String(value);
  }
  return result;
}

declare global {
  interface HTMLElementTagNameMap {
    "epistola-canvas": EpistolaCanvas;
  }
}
