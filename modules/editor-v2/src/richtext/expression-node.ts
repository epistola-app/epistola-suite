/**
 * TipTap ExpressionNode extension - vanilla JavaScript version.
 *
 * Renders expression chips like {{customer.name}} in text content.
 * No React dependency - uses vanilla DOM manipulation.
 */

import { mergeAttributes, Node, nodeInputRule } from "@tiptap/core";

/**
 * Expression evaluation function type.
 */
export type ExpressionEvaluator = (
  expression: string,
  context: Record<string, unknown>,
) => Promise<{ success: boolean; value?: unknown; error?: string }>;

/**
 * Format a value for display in an expression chip.
 */
function formatDisplayValue(value: unknown, expr: string): string {
  if (value === undefined) return `{{${expr}}}`;
  if (value === null) return "null";
  if (typeof value === "object") {
    const json = JSON.stringify(value);
    return json.length > 30 ? json.slice(0, 30) + "..." : json;
  }
  return String(value);
}

/**
 * Options for the ExpressionNode extension.
 */
export interface ExpressionNodeOptions {
  /**
   * Callback to evaluate an expression.
   * Called whenever an expression needs to be rendered.
   */
  evaluate?: ExpressionEvaluator;

  /**
   * Context data for expression evaluation.
   */
  context?: Record<string, unknown>;

  /**
   * Callback when an expression chip is clicked (for editing).
   */
  onExpressionClick?: (
    expression: string,
    element: HTMLElement,
    update: (newExpression: string) => void,
  ) => void;
}

/**
 * TipTap node extension for inline expressions.
 *
 * @example
 * ```typescript
 * import { Editor } from '@tiptap/core';
 * import StarterKit from '@tiptap/starter-kit';
 * import { ExpressionNode } from './expression-node';
 *
 * const editor = new Editor({
 *   extensions: [
 *     StarterKit,
 *     ExpressionNode.configure({
 *       evaluate: async (expr, ctx) => {
 *         // Evaluate expression and return result
 *         return { success: true, value: ctx[expr] };
 *       },
 *       context: { name: 'John' },
 *       onExpressionClick: (expr, el, update) => {
 *         // Show expression editor
 *       },
 *     }),
 *   ],
 * });
 * ```
 */
export const ExpressionNode = Node.create<ExpressionNodeOptions>({
  name: "expression",

  group: "inline",

  inline: true,

  atom: true,

  addOptions() {
    return {
      evaluate: undefined,
      context: {},
      onExpressionClick: undefined,
    };
  },

  addAttributes() {
    return {
      expression: {
        default: "",
      },
    };
  },

  parseHTML() {
    return [
      {
        tag: "span[data-expression]",
        getAttrs: (node) => {
          if (typeof node === "string") return false;
          const element = node as HTMLElement;
          return {
            expression: element.getAttribute("data-expression") || "",
          };
        },
      },
    ];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      "span",
      mergeAttributes(HTMLAttributes, {
        "data-expression": HTMLAttributes.expression,
        class: "ev2-expression-chip",
      }),
      `{{${HTMLAttributes.expression}}}`,
    ];
  },

  addNodeView() {
    return ({ node, getPos, editor }) => {
      const dom = document.createElement("span");
      dom.className = "ev2-expression-chip";
      dom.contentEditable = "false";

      const expression = node.attrs.expression;
      dom.dataset.expression = expression;
      dom.textContent = `{{${expression}}}`;
      dom.title = `Expression: ${expression}`;

      // Evaluate expression if evaluator is provided
      const { evaluate, context, onExpressionClick } = this.options;
      if (evaluate && expression) {
        evaluate(expression, context ?? {}).then((result) => {
          if (result.success) {
            dom.textContent = formatDisplayValue(result.value, expression);
          } else {
            dom.textContent = `{{${expression}}}`;
            dom.classList.add("ev2-expression-chip--error");
            dom.title = `Error: ${result.error}`;
          }
        });
      }

      // Handle click for editing
      if (onExpressionClick) {
        dom.addEventListener("click", (e) => {
          e.preventDefault();
          e.stopPropagation();

          onExpressionClick(expression, dom, (newExpression: string) => {
            if (typeof getPos === "function") {
              const pos = getPos();
              if (typeof pos === "number") {
                editor
                  .chain()
                  .focus()
                  .command(({ tr }) => {
                    tr.setNodeMarkup(pos, undefined, {
                      expression: newExpression,
                    });
                    return true;
                  })
                  .run();
              }
            }
          });
        });
        dom.style.cursor = "pointer";
      }

      return {
        dom,
        update: (updatedNode) => {
          if (updatedNode.type.name !== this.name) {
            return false;
          }
          const newExpression = updatedNode.attrs.expression;
          dom.dataset.expression = newExpression;
          dom.textContent = `{{${newExpression}}}`;
          dom.title = `Expression: ${newExpression}`;

          // Re-evaluate
          if (evaluate && newExpression) {
            evaluate(newExpression, context ?? {}).then((result) => {
              if (result.success) {
                dom.textContent = formatDisplayValue(result.value, newExpression);
                dom.classList.remove("ev2-expression-chip--error");
              } else {
                dom.textContent = `{{${newExpression}}}`;
                dom.classList.add("ev2-expression-chip--error");
                dom.title = `Error: ${result.error}`;
              }
            });
          }

          return true;
        },
        destroy: () => {
          // Cleanup if needed
        },
      };
    };
  },

  addCommands() {
    return {
      insertExpression:
        (expression: string) =>
        ({ commands }) => {
          return commands.insertContent({
            type: this.name,
            attrs: { expression },
          });
        },
    };
  },

  // Handle typing {{ to insert an expression
  addInputRules() {
    return [
      // Trigger on {{ followed by expression and }}
      nodeInputRule({
        find: /\{\{([^}]+)\}\}$/,
        type: this.type,
        getAttributes: (match) => ({
          expression: match[1],
        }),
      }),
    ];
  },
});

// Type declaration for commands
declare module "@tiptap/core" {
  interface Commands<ReturnType> {
    expression: {
      insertExpression: (expression: string) => ReturnType;
    };
  }
}
