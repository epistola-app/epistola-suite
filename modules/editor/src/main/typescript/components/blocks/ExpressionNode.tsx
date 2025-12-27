import { Node, mergeAttributes, nodeInputRule } from "@tiptap/core";
import { NodeViewWrapper, ReactNodeViewRenderer } from "@tiptap/react";
import type { NodeViewProps } from "@tiptap/react";
import { useState, useEffect } from "react";
import { useEditorStore } from "../../store/editorStore";
import { useScope } from "../../context/ScopeContext";
import { useEvaluator } from "../../context/EvaluatorContext";
import { ExpressionPopoverEditor } from "./ExpressionPopoverEditor";
import { buildEvaluationContext } from "@/lib/expression-utils";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";

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
  const [isOpen, setIsOpen] = useState(false);
  const [displayValue, setDisplayValue] = useState("[...]");
  const testData = useEditorStore((s) => s.testData);
  const scope = useScope();
  const { evaluate, isReady } = useEvaluator();

  const expr = node.attrs.expression;

  // Open popover automatically for newly created (empty) expressions
  useEffect(() => {
    if (!expr) {
      // Small delay to ensure the trigger element is mounted and positioned
      const timer = setTimeout(() => setIsOpen(true), 50);
      return () => clearTimeout(timer);
    }
  }, []); // Only run on mount

  // Lock body scroll when popover is open
  useEffect(() => {
    if (isOpen) {
      document.body.classList.add("overflow-hidden");
    } else {
      document.body.classList.remove("overflow-hidden");
    }
    return () => {
      document.body.classList.remove("overflow-hidden");
    };
  }, [isOpen]);

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
    setIsOpen(false);
  };

  const handleCancel = () => {
    // If expression is empty (user cancelled without entering anything), delete the node
    if (!node.attrs.expression) {
      deleteNode();
    } else {
      setIsOpen(false);
    }
  };

  return (
    <NodeViewWrapper as="span" className="inline-block align-middle">
      <Popover open={isOpen} onOpenChange={setIsOpen}>
        <PopoverTrigger asChild>
          <span
            className="expression-chip"
            title={`Click to edit: ${node.attrs.expression || "empty expression"}`}
          >
            {displayValue}
          </span>
        </PopoverTrigger>
        <PopoverContent
          className="w-auto p-4"
          align="start"
          side="bottom"
          sideOffset={8}
          onInteractOutside={(e) => {
            // Prevent closing when interacting with CodeMirror autocomplete
            const target = e.target as Element;
            if (target?.closest(".cm-tooltip-autocomplete") || target?.closest(".cm-tooltip")) {
              e.preventDefault();
            }
          }}
          onOpenAutoFocus={(e) => {
            // Let CodeMirror handle its own focus
            e.preventDefault();
          }}
        >
          <ExpressionPopoverEditor
            value={node.attrs.expression}
            onSave={handleSave}
            onCancel={handleCancel}
          />
        </PopoverContent>
      </Popover>
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
