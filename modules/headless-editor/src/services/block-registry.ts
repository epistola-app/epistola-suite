import type {
  Block,
  BlockCatalogItem,
  BlockDefinition,
  BlockToolbarConfig,
  BlockType,
  ColumnsBlock,
  TableBlock,
} from "../types.js";

export class BlockRegistry {
  private readonly blockDefinitions: Record<string, BlockDefinition>;
  private readonly blockToolbarConfig: Record<string, BlockToolbarConfig>;
  private readonly blockDropContainerResolvers: Record<
    string,
    (block: Block) => string[]
  >;

  constructor(definitions: BlockDefinition[]) {
    this.blockDefinitions = Object.fromEntries(
      definitions.map((definition) => [definition.type, definition]),
    );

    this.blockToolbarConfig = Object.fromEntries(
      definitions.map((definition) => [
        definition.type,
        typeof definition.toolbar === "boolean"
          ? {
              visible: definition.toolbar,
              order: 0,
              group: definition.category ?? "Blocks",
              label: definition.label ?? definition.type,
              icon: definition.icon,
            }
          : (definition.toolbar ?? {
              visible: true,
              order: 0,
              group: definition.category ?? "Blocks",
              label: definition.label ?? definition.type,
              icon: definition.icon,
            }),
      ]),
    );

    this.blockDropContainerResolvers = Object.fromEntries(
      definitions.map((definition) => [
        definition.type,
        definition.dropContainers ?? (() => []),
      ]),
    );
  }

  getDefinition(type: BlockType): BlockDefinition | undefined {
    return this.blockDefinitions[type];
  }

  getTypes(): BlockType[] {
    return Object.keys(this.blockDefinitions) as BlockType[];
  }

  getCatalog(): BlockCatalogItem[] {
    const entries: BlockCatalogItem[] = Object.entries(this.blockDefinitions).map(
      ([type, definition]) => {
        const blockType = type as BlockType;
        const toolbar = this.blockToolbarConfig[type] ?? {
          visible: true,
          order: 0,
          group: definition.category ?? "Blocks",
          label: definition.label ?? blockType,
          icon: definition.icon,
        };

        const constraints = definition.constraints;
        const addableAtRoot =
          constraints.allowedParentTypes === null ||
          constraints.allowedParentTypes.includes("root");

        return {
          type: blockType,
          label: toolbar.label ?? definition.label ?? blockType,
          icon: toolbar.icon ?? definition.icon,
          group: toolbar.group ?? definition.category ?? "Blocks",
          order: toolbar.order ?? 0,
          visible: toolbar.visible !== false,
          addableAtRoot,
        };
      },
    );

    return entries.sort((a, b) => {
      if (a.group !== b.group) return a.group.localeCompare(b.group);
      if (a.order !== b.order) return a.order - b.order;
      return a.label.localeCompare(b.label);
    });
  }

  resolveDropContainers(block: Block): string[] {
    const resolver = this.blockDropContainerResolvers[block.type];
    return resolver ? resolver(block) : [];
  }

  getDropContainerIds(blocks: Block[]): string[] {
    const ids = new Set<string>();

    const collect = (children: Block[]) => {
      for (const block of children) {
        const containerIds = this.resolveDropContainers(block);
        for (const id of containerIds) {
          ids.add(id);
        }

        if ("children" in block && Array.isArray(block.children)) {
          collect(block.children);
        }

        if (block.type === "columns") {
          const columnsBlock = block as ColumnsBlock;
          for (const column of columnsBlock.columns) {
            collect(column.children);
          }
        }

        if (block.type === "table") {
          const tableBlock = block as TableBlock;
          for (const row of tableBlock.rows) {
            for (const cell of row.cells) {
              collect(cell.children);
            }
          }
        }
      }
    };

    collect(blocks);
    return [...ids];
  }
}
