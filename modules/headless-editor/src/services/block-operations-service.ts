import { BlockTree } from "../store.js";
import type {
  Block,
  BlockType,
  ColumnsBlock,
  EditorCallbacks,
  TableBlock,
  Template,
  ValidationResult,
} from "../types.js";
import { BlockRegistry } from "./block-registry.js";
import { EditorStateRepository } from "./editor-state-repository.js";

type RecordMutation = () => void;
type GetCallbacks = () => EditorCallbacks;
type GenerateId = () => string;

export class BlockOperationsService {
  constructor(
    private readonly stateRepository: EditorStateRepository,
    private readonly blockRegistry: BlockRegistry,
    private readonly recordMutation: RecordMutation,
    private readonly getCallbacks: GetCallbacks,
    private readonly generateId: GenerateId,
  ) {}

  addBlock(
    type: BlockType,
    parentId: string | null = null,
    index: number = -1,
  ): Block | null {
    const definition = this.blockRegistry.getDefinition(type);
    if (!definition) {
      this.getCallbacks().onError?.(new Error(`Unknown block type: ${type}`));
      return null;
    }

    if (parentId !== null) {
      const validationResult = this.validateParentForChild(parentId, type);
      if (!validationResult.valid) {
        this.getCallbacks().onError?.(new Error(validationResult.errors[0]));
        return null;
      }
    } else if (
      definition.constraints.allowedParentTypes !== null &&
      !definition.constraints.allowedParentTypes.includes("root")
    ) {
      this.getCallbacks().onError?.(
        new Error(`Block type ${type} cannot be placed at root level`),
      );
      return null;
    }

    const newBlock = definition.create(this.generateId());
    if (this.getCallbacks().onBeforeBlockAdd?.(newBlock, parentId) === false) {
      return null;
    }

    this.recordMutation();

    const template = this.stateRepository.getTemplate();
    const targetIndex = index === -1 ? this.getChildCount(parentId) : index;
    const newBlocks = BlockTree.addBlock(
      template.blocks,
      newBlock,
      parentId,
      targetIndex,
    );

    this.stateRepository.setTemplate({ ...template, blocks: newBlocks });
    return newBlock;
  }

  updateBlock(id: string, updates: Partial<Block>): void {
    this.recordMutation();

    const template = this.stateRepository.getTemplate();
    const newBlocks = BlockTree.updateBlock(template.blocks, id, updates);
    this.stateRepository.setTemplate({ ...template, blocks: newBlocks });
  }

  deleteBlock(id: string): boolean {
    if (this.getCallbacks().onBeforeBlockDelete?.(id) === false) {
      return false;
    }

    this.recordMutation();

    const template = this.stateRepository.getTemplate();
    const newBlocks = BlockTree.removeBlock(template.blocks, id);
    this.stateRepository.setTemplate({ ...template, blocks: newBlocks });

    if (this.stateRepository.getSelectedBlockId() === id) {
      this.stateRepository.setSelectedBlockId(null);
    }

    return true;
  }

  moveBlock(id: string, newParentId: string | null, newIndex: number): void {
    this.recordMutation();

    const template = this.stateRepository.getTemplate();
    const newBlocks = BlockTree.moveBlock(
      template.blocks,
      id,
      newParentId,
      newIndex,
    );
    this.stateRepository.setTemplate({ ...template, blocks: newBlocks });
  }

  findBlock(id: string): Block | null {
    return BlockTree.findBlock(this.stateRepository.getTemplate().blocks, id);
  }

  addColumn(columnsBlockId: string): void {
    const block = this.findBlock(columnsBlockId);
    if (!block || block.type !== "columns") {
      this.getCallbacks().onError?.(new Error("Target is not a columns block"));
      return;
    }

    const columnsBlock = block as ColumnsBlock;
    if (columnsBlock.columns.length >= 6) {
      this.getCallbacks().onError?.(new Error("Maximum 6 columns allowed"));
      return;
    }

    this.recordMutation();

    const newColumn = {
      id: this.generateId(),
      size: 1,
      children: [],
    };

    this.updateBlock(columnsBlockId, {
      columns: [...columnsBlock.columns, newColumn],
    } as Partial<ColumnsBlock>);
  }

  removeColumn(columnsBlockId: string, columnId: string): void {
    const block = this.findBlock(columnsBlockId);
    if (!block || block.type !== "columns") {
      this.getCallbacks().onError?.(new Error("Target is not a columns block"));
      return;
    }

    const columnsBlock = block as ColumnsBlock;
    if (columnsBlock.columns.length <= 1) {
      this.getCallbacks().onError?.(new Error("Cannot remove last column"));
      return;
    }

    this.recordMutation();

    this.updateBlock(columnsBlockId, {
      columns: columnsBlock.columns.filter((col) => col.id !== columnId),
    } as Partial<ColumnsBlock>);
  }

  addRow(tableBlockId: string, isHeader = false): void {
    const block = this.findBlock(tableBlockId);
    if (!block || block.type !== "table") {
      this.getCallbacks().onError?.(new Error("Target is not a table block"));
      return;
    }

    const tableBlock = block as TableBlock;
    const cellCount = tableBlock.rows[0]?.cells.length ?? 3;

    this.recordMutation();

    const newRow = {
      id: this.generateId(),
      cells: Array.from({ length: cellCount }, () => ({
        id: this.generateId(),
        children: [],
      })),
      isHeader,
    };

    this.updateBlock(tableBlockId, {
      rows: [...tableBlock.rows, newRow],
    } as Partial<TableBlock>);
  }

  removeRow(tableBlockId: string, rowId: string): void {
    const block = this.findBlock(tableBlockId);
    if (!block || block.type !== "table") {
      this.getCallbacks().onError?.(new Error("Target is not a table block"));
      return;
    }

    const tableBlock = block as TableBlock;
    if (tableBlock.rows.length <= 1) {
      this.getCallbacks().onError?.(new Error("Cannot remove last row"));
      return;
    }

    this.recordMutation();

    this.updateBlock(tableBlockId, {
      rows: tableBlock.rows.filter((row) => row.id !== rowId),
    } as Partial<TableBlock>);
  }

  validateTemplate(template: Template): ValidationResult {
    const errors: string[] = [];

    const validateBlocks = (blocks: Block[]) => {
      for (const block of blocks) {
        const definition = this.blockRegistry.getDefinition(block.type);
        if (!definition) {
          errors.push(`Unknown block type: ${block.type}`);
          continue;
        }

        const result = definition.validate(block);
        if (!result.valid) {
          errors.push(...result.errors);
        }

        if ("children" in block && Array.isArray(block.children)) {
          validateBlocks(block.children);
        }

        if (block.type === "columns") {
          const columnsBlock = block as ColumnsBlock;
          for (const col of columnsBlock.columns) {
            validateBlocks(col.children);
          }
        }

        if (block.type === "table") {
          const tableBlock = block as TableBlock;
          for (const row of tableBlock.rows) {
            for (const cell of row.cells) {
              validateBlocks(cell.children);
            }
          }
        }
      }
    };

    validateBlocks(template.blocks);
    return { valid: errors.length === 0, errors } as ValidationResult;
  }

  private validateParentForChild(
    parentId: string,
    childType: BlockType,
  ): ValidationResult {
    const template = this.stateRepository.getTemplate();
    const errors: string[] = [];

    const parent = BlockTree.findBlock(template.blocks, parentId);
    if (parent) {
      const parentDef = this.blockRegistry.getDefinition(parent.type);
      if (!parentDef) {
        errors.push(`Unknown parent block type: ${parent.type}`);
        return { valid: false, errors };
      }

      if (!parentDef.constraints.canHaveChildren) {
        errors.push(`Block type ${parent.type} cannot have children`);
        return { valid: false, errors };
      }

      if (
        parentDef.constraints.allowedChildTypes !== null &&
        !parentDef.constraints.allowedChildTypes.includes(childType)
      ) {
        errors.push(
          `Block type ${parent.type} does not accept ${childType} children`,
        );
        return { valid: false, errors };
      }

      const childDef = this.blockRegistry.getDefinition(childType);
      if (
        childDef &&
        childDef.constraints.allowedParentTypes !== null &&
        !childDef.constraints.allowedParentTypes.includes(parent.type)
      ) {
        errors.push(`Block type ${childType} cannot be nested in ${parent.type}`);
        return { valid: false, errors };
      }

      return { valid: true };
    }

    const columnResult = BlockTree.findColumn(template.blocks, parentId);
    if (columnResult) {
      const childDef = this.blockRegistry.getDefinition(childType);
      if (
        childDef &&
        childDef.constraints.allowedParentTypes !== null &&
        !childDef.constraints.allowedParentTypes.includes("columns")
      ) {
        errors.push(`Block type ${childType} cannot be nested in a column`);
        return { valid: false, errors };
      }
      return { valid: true };
    }

    const cellResult = BlockTree.findCell(template.blocks, parentId);
    if (cellResult) {
      const childDef = this.blockRegistry.getDefinition(childType);
      if (
        childDef &&
        childDef.constraints.allowedParentTypes !== null &&
        !childDef.constraints.allowedParentTypes.includes("table")
      ) {
        errors.push(`Block type ${childType} cannot be nested in a table cell`);
        return { valid: false, errors };
      }
      return { valid: true };
    }

    errors.push(`Parent not found: ${parentId}`);
    return { valid: false, errors };
  }

  private getChildCount(parentId: string | null): number {
    return BlockTree.getChildCount(this.stateRepository.getTemplate().blocks, parentId);
  }
}
