import { BlockTree } from "../store.js";
import type {
  Block,
  ColumnsBlock,
  DragDropPort,
  DropPosition,
  DropZone,
  TableBlock,
} from "../types.js";
import { BlockOperationsService } from "./block-operations-service.js";
import { BlockRegistry } from "./block-registry.js";
import { EditorStateRepository } from "./editor-state-repository.js";

export class DragDropService {
  constructor(
    private readonly stateRepository: EditorStateRepository,
    private readonly blockRegistry: BlockRegistry,
    private readonly blockOperations: BlockOperationsService,
    private readonly onError: (error: Error) => void,
  ) {}

  canDrag(blockId: string): boolean {
    const block = this.blockOperations.findBlock(blockId);
    if (!block) return false;

    const definition = this.blockRegistry.getDefinition(block.type);
    if (!definition) return false;

    return definition.constraints.canBeDragged;
  }

  canDrop(
    draggedId: string,
    targetId: string | null,
    position: DropPosition,
  ): boolean {
    const dragged = this.blockOperations.findBlock(draggedId);
    if (!dragged) return false;

    const draggedDef = this.blockRegistry.getDefinition(dragged.type);
    if (!draggedDef) return false;

    if (!draggedDef.constraints.canBeDragged) return false;
    if (draggedId === targetId) return false;

    if (targetId === null) {
      const allowedParents = draggedDef.constraints.allowedParentTypes;
      return allowedParents === null || allowedParents.includes("root");
    }

    const target = this.blockOperations.findBlock(targetId);
    if (!target) return false;

    const targetDef = this.blockRegistry.getDefinition(target.type);
    if (!targetDef) return false;

    if (this.isDescendant(draggedId, targetId)) return false;

    if (position === "inside") {
      if (!targetDef.constraints.canHaveChildren) return false;

      const allowedChildren = targetDef.constraints.allowedChildTypes;
      if (allowedChildren !== null && !allowedChildren.includes(dragged.type)) {
        return false;
      }

      const allowedParents = draggedDef.constraints.allowedParentTypes;
      if (allowedParents !== null && !allowedParents.includes(target.type)) {
        return false;
      }

      if (targetDef.constraints.maxChildren !== undefined) {
        const currentChildren = "children" in target ? target.children.length : 0;
        if (currentChildren >= targetDef.constraints.maxChildren) {
          return false;
        }
      }

      return true;
    }

    const targetParent = BlockTree.findParent(
      this.stateRepository.getTemplate().blocks,
      targetId,
      null,
    );

    if (targetParent === null) {
      const allowedParents = draggedDef.constraints.allowedParentTypes;
      return allowedParents === null || allowedParents.includes("root");
    }

    const targetParentDef = this.blockRegistry.getDefinition(targetParent.type);
    if (!targetParentDef) return false;

    const allowedChildren = targetParentDef.constraints.allowedChildTypes;
    if (allowedChildren !== null && !allowedChildren.includes(dragged.type)) {
      return false;
    }

    const allowedParents = draggedDef.constraints.allowedParentTypes;
    if (
      allowedParents !== null &&
      !allowedParents.includes(targetParent.type)
    ) {
      return false;
    }

    return true;
  }

  getDropZones(draggedId: string): DropZone[] {
    const zones: DropZone[] = [];
    const template = this.stateRepository.getTemplate();

    if (this.canDrop(draggedId, null, "inside")) {
      zones.push({ targetId: null, position: "inside", targetType: null });
    }

    const checkBlock = (block: Block) => {
      if (this.canDrop(draggedId, block.id, "inside")) {
        zones.push({
          targetId: block.id,
          position: "inside",
          targetType: block.type,
        });
      }

      if (this.canDrop(draggedId, block.id, "before")) {
        zones.push({
          targetId: block.id,
          position: "before",
          targetType: block.type,
        });
      }
      if (this.canDrop(draggedId, block.id, "after")) {
        zones.push({
          targetId: block.id,
          position: "after",
          targetType: block.type,
        });
      }

      if ("children" in block && Array.isArray(block.children)) {
        for (const child of block.children) {
          checkBlock(child);
        }
      }

      if (block.type === "columns") {
        const columnsBlock = block as ColumnsBlock;
        for (const col of columnsBlock.columns) {
          for (const child of col.children) {
            checkBlock(child);
          }
        }
      }

      if (block.type === "table") {
        const tableBlock = block as TableBlock;
        for (const row of tableBlock.rows) {
          for (const cell of row.cells) {
            for (const child of cell.children) {
              checkBlock(child);
            }
          }
        }
      }
    };

    for (const block of template.blocks) {
      checkBlock(block);
    }

    return zones;
  }

  drop(
    draggedId: string,
    targetId: string | null,
    index: number,
    position: DropPosition = "inside",
  ): void {
    if (!this.canDrop(draggedId, targetId, position)) {
      this.onError(new Error("Invalid drop target"));
      return;
    }

    if (position === "inside") {
      this.blockOperations.moveBlock(draggedId, targetId, index);
      return;
    }

    const target = this.blockOperations.findBlock(targetId!);
    if (!target) return;

    const targetParent = BlockTree.findParent(
      this.stateRepository.getTemplate().blocks,
      targetId!,
      null,
    );
    const actualIndex =
      position === "after"
        ? BlockTree.getChildIndex(
            this.stateRepository.getTemplate().blocks,
            targetId!,
            null,
          ) + 1
        : BlockTree.getChildIndex(
            this.stateRepository.getTemplate().blocks,
            targetId!,
            null,
          );

    this.blockOperations.moveBlock(draggedId, targetParent?.id ?? null, actualIndex);
  }

  getPort(): DragDropPort {
    return {
      canDrag: this.canDrag.bind(this),
      canDrop: this.canDrop.bind(this),
      getDropZones: this.getDropZones.bind(this),
      drop: this.drop.bind(this),
    };
  }

  private isDescendant(
    ancestorId: string,
    potentialDescendantId: string,
  ): boolean {
    const ancestor = this.blockOperations.findBlock(ancestorId);
    if (!ancestor) return false;

    const checkInBlocks = (blocks: Block[]): boolean => {
      for (const block of blocks) {
        if (block.id === potentialDescendantId) return true;
        if ("children" in block && Array.isArray(block.children)) {
          if (checkInBlocks(block.children)) return true;
        }
        if (block.type === "columns") {
          const columnsBlock = block as ColumnsBlock;
          for (const col of columnsBlock.columns) {
            if (checkInBlocks(col.children)) return true;
          }
        }
        if (block.type === "table") {
          const tableBlock = block as TableBlock;
          for (const row of tableBlock.rows) {
            for (const cell of row.cells) {
              if (checkInBlocks(cell.children)) return true;
            }
          }
        }
      }
      return false;
    };

    if ("children" in ancestor && Array.isArray(ancestor.children)) {
      if (checkInBlocks(ancestor.children)) return true;
    }
    if (ancestor.type === "columns") {
      const columnsBlock = ancestor as ColumnsBlock;
      for (const col of columnsBlock.columns) {
        if (checkInBlocks(col.children)) return true;
      }
    }
    if (ancestor.type === "table") {
      const tableBlock = ancestor as TableBlock;
      for (const row of tableBlock.rows) {
        for (const cell of row.cells) {
          if (checkInBlocks(cell.children)) return true;
        }
      }
    }

    return false;
  }
}
