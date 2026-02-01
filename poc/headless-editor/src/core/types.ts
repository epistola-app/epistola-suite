/**
 * Base block interface - all blocks extend this
 */
export interface BaseBlock {
  id: string;
  type: string;
}

/**
 * Text block - simple text content
 */
export interface TextBlock extends BaseBlock {
  type: 'text';
  content: string;
}

/**
 * Container block - holds child blocks
 */
export interface ContainerBlock extends BaseBlock {
  type: 'container';
  children: Block[];
}

/**
 * Columns block - layout with column children only
 */
export interface ColumnsBlock extends BaseBlock {
  type: 'columns';
  children: Block[];
}

/**
 * Column block - lives inside columns only
 */
export interface ColumnBlock extends BaseBlock {
  type: 'column';
  children: Block[];
}

/**
 * Union of all block types
 */
export type Block = TextBlock | ContainerBlock | ColumnsBlock | ColumnBlock;

/**
 * Template structure
 */
export interface Template {
  id: string;
  name: string;
  blocks: Block[];
}

/**
 * Block constraints - block declares its own rules
 */
export interface BlockConstraints {
  // As a container
  canHaveChildren: boolean;
  allowedChildTypes: string[] | null; // null = any, [] = none
  maxChildren?: number;

  // As a draggable item
  canBeDragged: boolean;
  canBeNested: boolean;
  allowedParentTypes: string[] | null; // null = any, ['root'] = only root
}

/**
 * Block definition - describes what a block can do (logic only, no UI)
 */
export interface BlockDefinition {
  type: string;

  /** Create a new block with defaults */
  create: (id: string) => Block;

  /** Validate block structure */
  validate: (block: Block) => ValidationResult;

  /** Block declares its own constraints */
  constraints: BlockConstraints;
}

/**
 * Drop position relative to target
 */
export type DropPosition = 'before' | 'after' | 'inside';

/**
 * Drop zone info for UI hints
 */
export interface DropZone {
  targetId: string | null; // null = root
  position: DropPosition;
  targetType: string | null;
}

/**
 * Drag & Drop Port - editor provides, user's adapter consumes
 */
export interface DragDropPort {
  /** Can this block be dragged? */
  canDrag(blockId: string): boolean;

  /** Can this block be dropped at this location? */
  canDrop(draggedId: string, targetId: string | null, position: DropPosition): boolean;

  /** Get valid drop zones for visual hints */
  getDropZones(draggedId: string): DropZone[];

  /** Execute the drop */
  drop(draggedId: string, targetId: string | null, index: number): void;
}

export interface ValidationResult {
  valid: boolean;
  errors: string[];
}

/**
 * Editor state
 */
export interface EditorState {
  template: Template;
  selectedBlockId: string | null;
}

/**
 * Editor callbacks
 */
export interface EditorCallbacks {
  onTemplateChange?: (template: Template) => void;
  onBlockSelect?: (blockId: string | null) => void;
  onBeforeBlockAdd?: (block: Block, parentId: string | null) => boolean;
  onBeforeBlockDelete?: (blockId: string) => boolean;
  onError?: (error: Error) => void;
}

/**
 * Editor configuration
 */
export interface EditorConfig {
  template?: Template;
  blocks?: Record<string, BlockDefinition>;
  callbacks?: EditorCallbacks;
}
