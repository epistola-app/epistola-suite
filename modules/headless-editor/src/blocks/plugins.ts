import type { BlockDefinition, BlockPlugin } from "../types.js";

function defaultDropContainers(type: string, block: any): string[] {
  switch (type) {
    case "container":
    case "conditional":
    case "loop":
    case "pageheader":
    case "pagefooter":
      return [block.id];
    case "columns":
      return Array.isArray(block.columns)
        ? block.columns.map((column: { id: string }) => column.id)
        : [];
    case "table":
      return Array.isArray(block.rows)
        ? block.rows.flatMap((row: { cells: { id: string }[] }) =>
            row.cells.map((cell) => cell.id),
          )
        : [];
    default:
      return [];
  }
}

export function blockDefinitionToPlugin(
  definition: BlockDefinition,
): BlockPlugin {
  return {
    type: definition.type,
    create: definition.create,
    validate: definition.validate,
    constraints: definition.constraints,
    dropContainers: (block) => defaultDropContainers(definition.type, block),
    label: definition.label,
    icon: definition.icon,
    category: definition.category,
    capabilities: {
      html: true,
      pdf: true,
    },
    toolbar: {
      visible: true,
      order: 0,
      group: definition.category ?? "Blocks",
      label: definition.label,
      icon: definition.icon,
    },
  };
}

export function blockDefinitionsToPlugins(
  definitions: Record<string, BlockDefinition>,
): BlockPlugin[] {
  return Object.values(definitions).map((definition) =>
    blockDefinitionToPlugin(definition),
  );
}
