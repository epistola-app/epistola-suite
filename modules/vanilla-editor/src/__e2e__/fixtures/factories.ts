import type { TemplateModel, Block, TextBlock, ContainerBlock, ConditionalBlock, LoopBlock } from "@epistola/headless-editor";

/**
 * Factory functions for creating test template data.
 * Provides type-safe builders with sensible defaults.
 */

export interface TemplateFactoryOptions {
  id?: string;
  name?: string;
  blocks?: Block[];
}

export function createTemplate(options: TemplateFactoryOptions = {}): TemplateModel {
  return {
    id: options.id ?? `test-${Math.random().toString(36).substring(7)}`,
    name: options.name ?? "Test Template",
    blocks: options.blocks ?? [],
    styles: {},
    pageSettings: {
      format: "A4",
      orientation: "portrait",
      margins: { top: 20, right: 20, bottom: 20, left: 20 },
    },
  };
}

export function createTextBlock(content: string): TextBlock {
  return {
    id: `block-${Math.random().toString(36).substring(7)}`,
    type: "text",
    content: {
      type: "doc",
      content: [
        {
          type: "paragraph",
          content: [{ type: "text", text: content }],
        },
      ],
    },
    styles: {},
    children: [],
  };
}

export function createContainerBlock(children: Block[] = []): ContainerBlock {
  return {
    id: `container-${Math.random().toString(36).substring(7)}`,
    type: "container",
    styles: {},
    children,
  };
}

export function createConditionalBlock(condition: string): ConditionalBlock {
  return {
    id: `conditional-${Math.random().toString(36).substring(7)}`,
    type: "conditional",
    condition: { raw: condition },
    styles: {},
    children: [],
  };
}

export function createLoopBlock(alias: string, expression: string): LoopBlock {
  return {
    id: `loop-${Math.random().toString(36).substring(7)}`,
    type: "loop",
    loop: { alias, expression },
    styles: {},
    children: [],
  };
}
