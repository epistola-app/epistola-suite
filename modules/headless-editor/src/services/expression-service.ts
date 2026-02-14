import {
  evaluateJsonata,
  evaluateJsonataArray,
  evaluateJsonataBoolean,
  evaluateJsonataString,
} from "../evaluator/index.js";
import type {
  EvaluationContext,
  EvaluationResult,
  ScopeVariable,
} from "../evaluator/index.js";
import { BlockTree } from "../store.js";
import type {
  ConditionalBlock,
  LoopBlock,
  TipTapContent,
} from "../types.js";
import { EditorStateRepository } from "./editor-state-repository.js";

export class ExpressionService {
  constructor(private readonly stateRepository: EditorStateRepository) {}

  getScopeVariables(blockId: string): ScopeVariable[] {
    const template = this.stateRepository.getTemplate();
    const block = BlockTree.findBlock(template.blocks, blockId);
    if (!block) return [];

    const variables: ScopeVariable[] = [];
    let currentId: string | null = blockId;

    while (currentId !== null) {
      const parent = BlockTree.findParent(template.blocks, currentId, null);
      if (parent === null) break;

      if (parent.type === "loop") {
        const loopBlock = parent as LoopBlock;
        const loopVars: ScopeVariable[] = [
          {
            name: loopBlock.itemAlias,
            type: "loop-item",
            arrayPath: loopBlock.expression.raw,
          },
        ];
        if (loopBlock.indexAlias) {
          loopVars.push({
            name: loopBlock.indexAlias,
            type: "loop-index",
            arrayPath: loopBlock.expression.raw,
          });
        }
        variables.unshift(...loopVars);
      }

      currentId = parent.id;
    }

    return variables;
  }

  getExpressionContext(blockId: string): EvaluationContext {
    const testData = this.stateRepository.getTestData();
    const scopeVars = this.getScopeVariables(blockId);

    if (scopeVars.length === 0) {
      return { ...testData };
    }

    const context: EvaluationContext = { ...testData };
    for (const v of scopeVars) {
      if (v.type === "loop-item") {
        context[v.name] = `<${v.name}>`;
      } else if (v.type === "loop-index") {
        context[v.name] = 0;
      }
    }

    return context;
  }

  async evaluateExpression(
    expression: string,
    scope?: EvaluationContext,
  ): Promise<EvaluationResult> {
    const testData = this.stateRepository.getTestData();
    const context: EvaluationContext = { ...testData, ...scope };
    return evaluateJsonata(expression, context);
  }

  async evaluateCondition(
    blockId: string,
    scope?: EvaluationContext,
  ): Promise<boolean> {
    const template = this.stateRepository.getTemplate();
    const block = BlockTree.findBlock(template.blocks, blockId);
    if (!block || block.type !== "conditional") return false;

    const conditionalBlock = block as ConditionalBlock;

    const overrides = this.stateRepository.getPreviewOverrides();
    const override = overrides.conditionals[blockId];
    if (override === "show") return true;
    if (override === "hide") return false;

    const testData = this.stateRepository.getTestData();
    const context: EvaluationContext = { ...testData, ...scope };
    let result = await evaluateJsonataBoolean(
      conditionalBlock.condition.raw,
      context,
    );

    if (conditionalBlock.inverse) {
      result = !result;
    }

    return result;
  }

  async evaluateLoopArray(
    blockId: string,
    scope?: EvaluationContext,
  ): Promise<unknown[]> {
    const template = this.stateRepository.getTemplate();
    const block = BlockTree.findBlock(template.blocks, blockId);
    if (!block || block.type !== "loop") return [];

    const loopBlock = block as LoopBlock;

    const testData = this.stateRepository.getTestData();
    const context: EvaluationContext = { ...testData, ...scope };
    return evaluateJsonataArray(loopBlock.expression.raw, context);
  }

  async getLoopIterationCount(
    blockId: string,
    scope?: EvaluationContext,
  ): Promise<number> {
    const overrides = this.stateRepository.getPreviewOverrides();
    const override = overrides.loops[blockId];
    if (typeof override === "number") return override;

    const array = await this.evaluateLoopArray(blockId, scope);
    return array.length;
  }

  async buildLoopIterationContext(
    blockId: string,
    index: number,
    scope?: EvaluationContext,
  ): Promise<EvaluationContext> {
    const template = this.stateRepository.getTemplate();
    const block = BlockTree.findBlock(template.blocks, blockId);
    if (!block || block.type !== "loop") return { ...scope };

    const loopBlock = block as LoopBlock;
    const array = await this.evaluateLoopArray(blockId, scope);
    const item = array[index];

    const iterationScope: EvaluationContext = {
      ...scope,
      [loopBlock.itemAlias]: item,
    };

    if (loopBlock.indexAlias) {
      iterationScope[loopBlock.indexAlias] = index;
    }

    return iterationScope;
  }

  async interpolateText(
    content: TipTapContent,
    scope?: EvaluationContext,
  ): Promise<string> {
    if (!content) return "";

    const testData = this.stateRepository.getTestData();
    const context: EvaluationContext = { ...testData, ...scope };

    const rawText = this.extractTextFromTipTap(content);

    const matches = rawText.matchAll(/\{\{([^}]+)\}\}/g);
    const replacements: Array<{ match: string; value: string }> = [];

    for (const match of matches) {
      const expr = match[1].trim();
      const value = await evaluateJsonataString(expr, context);
      replacements.push({ match: match[0], value });
    }

    let result = rawText;
    for (const { match, value } of replacements) {
      result = result.replace(match, value);
    }

    return result;
  }

  private extractTextFromTipTap(content: TipTapContent): string {
    if (!content || typeof content !== "object") return "";

    const extractFromNode = (node: Record<string, unknown>): string => {
      if (!node) return "";

      if (node.type === "expression" && node.attrs) {
        const attrs = node.attrs as Record<string, unknown>;
        if (attrs.expression) {
          return `{{${attrs.expression}}}`;
        }
      }

      if (node.type === "text" && typeof node.text === "string") {
        return node.text;
      }

      if (Array.isArray(node.content)) {
        return node.content
          .map((child) => extractFromNode(child as Record<string, unknown>))
          .join("");
      }

      return "";
    };

    return extractFromNode(content as Record<string, unknown>);
  }
}
