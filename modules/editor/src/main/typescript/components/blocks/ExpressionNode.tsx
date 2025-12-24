import { Node, mergeAttributes, nodeInputRule } from "@tiptap/core";
import { NodeViewWrapper, ReactNodeViewRenderer } from "@tiptap/react";
import type { NodeViewProps } from "@tiptap/react";
import { useState, useEffect } from "react";
import { useEditorStore } from "../../store/editorStore";
import { useScope } from "../../context/ScopeContext";
import { useEvaluator } from "../../context/EvaluatorContext";
import { ExpressionEditor, buildEvaluationContext } from "./ExpressionEditor";

// Format value for display
function formatDisplayValue(value: unknown, expr: string): string {
  if (value === undefined) return `[${expr}]`;
  if (value === null) return "null";
  if (typeof value === "object") {
    const json = JSON.stringify(value);
    return json.length > 30 ? json.slice(0, 30) + "..." : json;
  }
  return String(value);
}

// Expression Node View Component
function ExpressionNodeView({ node, updateAttributes, deleteNode }: NodeViewProps) {
  // Start in editing mode if expression is empty (just created)
  const [isEditing, setIsEditing] = useState(!node.attrs.expression);
  const [displayValue, setDisplayValue] = useState("[...]");
  const testData = useEditorStore((s) => s.testData);
  const scope = useScope();
  const { evaluate, isReady } = useEvaluator();

  const expr = node.attrs.expression;

  // Async evaluation
  useEffect(() => {
    if (!expr) {
      setDisplayValue("[empty]");
      return;
    }
    if (!isReady) {
      setDisplayValue("[...]");
      return;
    }

    setDisplayValue("[...]");

    const trimmed = expr.trim();
    const context = buildEvaluationContext(testData, scope.variables);
    let cancelled = false;

    evaluate(trimmed, context).then((result) => {
      if (cancelled) return;
      if (!result.success) {
        setDisplayValue(`[${expr}]`);
      } else {
        setDisplayValue(formatDisplayValue(result.value, expr));
      }
    });

    return () => {
      cancelled = true;
    };
  }, [expr, testData, scope.variables, evaluate, isReady]);

  const handleSave = (newExpression: string) => {
    if (newExpression.trim()) {
      updateAttributes({ expression: newExpression.trim() });
    } else {
      deleteNode();
    }
    setIsEditing(false);
  };

  const handleCancel = () => {
    // If expression is empty (user cancelled without entering anything), delete the node
    if (!node.attrs.expression) {
      deleteNode();
    } else {
      setIsEditing(false);
    }
  };

  return (
    <NodeViewWrapper as="span" className="inline-block align-middle">
      {isEditing ? (
        <span className="inline-block" onClick={(e) => e.stopPropagation()}>
          <ExpressionEditor
            value={node.attrs.expression}
            onSave={handleSave}
            onCancel={handleCancel}
          />
        </span>
      ) : (
        <span
          onClick={() => setIsEditing(true)}
          className="expression-chip"
          title={`Click to edit: ${node.attrs.expression}`}
        >
          {displayValue}
        </span>
      )}
    </NodeViewWrapper>
  );
}

// Tiptap Node Extension
export const ExpressionNode = Node.create({
  name: "expression",

  group: "inline",

  inline: true,

  atom: true,

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
      },
    ];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      "span",
      mergeAttributes(HTMLAttributes, {
        "data-expression": HTMLAttributes.expression,
        class: "expression-chip",
      }),
      HTMLAttributes.expression,
    ];
  },

  addNodeView() {
    return ReactNodeViewRenderer(ExpressionNodeView);
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
      // Trigger on {{ - immediately create empty expression node (opens in edit mode)
      nodeInputRule({
        find: /\{\{$/,
        type: this.type,
        getAttributes: () => ({
          expression: "",
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
