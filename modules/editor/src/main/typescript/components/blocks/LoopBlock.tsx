import { useDroppable } from "@dnd-kit/core";
import { useState, useMemo } from "react";
import type { LoopBlock } from "../../types/template";
import { useEditorStore } from "../../store/editorStore";
import { BlockRenderer } from "./BlockRenderer";
import { ExpressionPopoverEditor } from "./ExpressionPopoverEditor";
import { ScopeProvider } from "../../context/ScopeContext";
import type { ScopeVariable } from "../../context/ScopeContext";
import { BlockHeader } from "./BlockHeader";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";

interface LoopBlockProps {
  block: LoopBlock;
  isSelected?: boolean;
  dragAttributes?: React.HTMLAttributes<HTMLDivElement>;
  dragListeners?: React.HTMLAttributes<HTMLDivElement>;
  onDelete?: (e: React.MouseEvent) => void;
}

export function LoopBlockComponent({
  block,
  isSelected = false,
  dragAttributes,
  dragListeners,
  onDelete,
}: LoopBlockProps) {
  const updateBlock = useEditorStore((s) => s.updateBlock);
  const testData = useEditorStore((s) => s.testData);
  const previewOverrides = useEditorStore((s) => s.previewOverrides);
  const setPreviewOverride = useEditorStore((s) => s.setPreviewOverride);

  const [isEditingExpression, setIsEditingExpression] = useState(false);
  const [isEditingAlias, setIsEditingAlias] = useState(false);
  const [aliasInput, setAliasInput] = useState(block.itemAlias);
  const [aliasError, setAliasError] = useState<string | null>(null);

  // Validate variable name
  const isValidVariableName = (name: string): boolean => {
    return /^[a-zA-Z_$][a-zA-Z0-9_$]*$/.test(name);
  };

  // Get the actual array length from test data
  const getArrayValue = (): unknown[] | null => {
    try {
      const parts = block.expression.raw.split(".");
      let value: unknown = testData;
      for (const part of parts) {
        value = (value as Record<string, unknown>)?.[part];
      }
      return Array.isArray(value) ? value : null;
    } catch {
      return null;
    }
  };

  const arrayValue = getArrayValue();
  const actualLength = arrayValue?.length ?? 0;
  const isValidArray = arrayValue !== null;
  const override = previewOverrides.loops[block.id];
  const displayCount = override === "data" || override === undefined ? actualLength : override;

  const { setNodeRef, isOver } = useDroppable({
    id: `loop-${block.id}`,
    data: {
      type: "container",
      parentId: block.id,
      index: block.children.length,
    },
  });

  // Create scope variables for children
  const scopeVariables = useMemo((): ScopeVariable[] => {
    const vars: ScopeVariable[] = [
      {
        name: block.itemAlias,
        type: "loop-item",
        arrayPath: block.expression.raw,
      },
    ];
    if (block.indexAlias) {
      vars.push({
        name: block.indexAlias,
        type: "loop-index",
        arrayPath: block.expression.raw,
      });
    }
    return vars;
  }, [block.itemAlias, block.indexAlias, block.expression.raw]);

  const handleExpressionSave = (newExpression: string) => {
    updateBlock(block.id, { expression: { raw: newExpression } });
    setIsEditingExpression(false);
  };

  const handleAliasChange = (value: string) => {
    setAliasInput(value);
    if (value.trim() && !isValidVariableName(value.trim())) {
      setAliasError("Must be a valid variable name (letters, numbers, _, $)");
    } else {
      setAliasError(null);
    }
  };

  const handleAliasSave = () => {
    const trimmed = aliasInput.trim();
    if (trimmed && isValidVariableName(trimmed)) {
      updateBlock(block.id, { itemAlias: trimmed });
      setAliasError(null);
      setIsEditingAlias(false);
    } else if (!trimmed) {
      // Reset to original if empty
      setAliasInput(block.itemAlias);
      setAliasError(null);
      setIsEditingAlias(false);
    }
    // If invalid, keep editing so user can fix
  };

  // Collapsed view - shows just a hint that this is a loop
  if (!isSelected) {
    return (
      <div className="rounded-lg border border-purple-200 bg-purple-50/50">
        <BlockHeader
          title="LOOP"
          isSelected={isSelected}
          dragAttributes={dragAttributes}
          dragListeners={dragListeners}
          onDelete={onDelete}
        />
        {/* Minimal header */}
        <div className="px-2 py-1 flex items-center gap-2 text-xs text-purple-500 border-b border-purple-100">
          <span className="font-medium">↻</span>
          <span className="opacity-60">each</span>
          <code className="text-purple-600">{block.expression.raw}</code>
          <span className="opacity-60">as</span>
          <code className="text-purple-600">{block.itemAlias}</code>
          <span className="opacity-60 ml-auto">({actualLength} items)</span>
        </div>

        {/* Loop Content - simplified */}
        <div
          ref={setNodeRef}
          style={block.styles}
          className={`p-2 ${isOver ? "bg-purple-100" : ""}`}
        >
          <ScopeProvider variables={scopeVariables}>
            {block.children.length === 0 ? (
              <div className="text-purple-300 text-sm py-2 text-center">Empty loop template</div>
            ) : (
              <div className="space-y-2">
                {block.children.map((child, index) => (
                  <BlockRenderer key={child.id} block={child} index={index} parentId={block.id} />
                ))}
              </div>
            )}
          </ScopeProvider>
        </div>
      </div>
    );
  }

  // Expanded view - full editing controls
  return (
    <div
      className={`rounded-lg border ${isValidArray ? "bg-purple-50 border-purple-200" : "bg-red-50 border-red-200"}`}
    >
      <BlockHeader
        title="LOOP"
        isSelected={isSelected}
        dragAttributes={dragAttributes}
        dragListeners={dragListeners}
        onDelete={onDelete}
      />
      {/* Header */}
      <div
        className={`px-3 py-2 border-b ${isValidArray ? "border-purple-200" : "border-red-200"}`}
      >
        <div className="flex items-center gap-2 flex-wrap text-base!">
          <span
            className={`text-xs font-medium ${isValidArray ? "text-purple-600" : "text-red-600"}`}
          >
            EACH
          </span>

          <Popover open={isEditingExpression} onOpenChange={setIsEditingExpression}>
            <PopoverTrigger asChild>
              <code
                className={`px-2 py-0.5 text-sm rounded cursor-pointer ${
                  isValidArray
                    ? "bg-purple-100 hover:bg-purple-200"
                    : "bg-red-100 hover:bg-red-200 text-red-700"
                }`}
                title="Click to edit array expression"
              >
                {block.expression.raw || "..."}
                {!isValidArray && " ⚠️"}
              </code>
            </PopoverTrigger>
            <PopoverContent
              className="w-auto p-4"
              align="start"
              side="bottom"
              sideOffset={8}
              onInteractOutside={(e) => {
                const target = e.target as Element;
                if (target?.closest(".cm-tooltip-autocomplete") || target?.closest(".cm-tooltip")) {
                  e.preventDefault();
                }
              }}
              onOpenAutoFocus={(e) => e.preventDefault()}
            >
              <ExpressionPopoverEditor
                value={block.expression.raw}
                onSave={handleExpressionSave}
                onCancel={() => setIsEditingExpression(false)}
                filterArraysOnly
              />
            </PopoverContent>
          </Popover>

          <span className={`text-xs ${isValidArray ? "text-purple-500" : "text-red-500"}`}>as</span>

          {isEditingAlias ? (
            <div className="relative">
              <input
                type="text"
                value={aliasInput}
                onChange={(e) => handleAliasChange(e.target.value)}
                onBlur={handleAliasSave}
                onKeyDown={(e) => {
                  if (e.key === "Enter") handleAliasSave();
                  if (e.key === "Escape") {
                    setAliasInput(block.itemAlias);
                    setAliasError(null);
                    setIsEditingAlias(false);
                  }
                }}
                className={`px-2 py-0.5 text-sm bg-white border rounded focus:outline-none w-24 font-mono ${
                  aliasError
                    ? "border-red-400 focus:border-red-500"
                    : "border-purple-300 focus:border-purple-500"
                }`}
                autoFocus
                placeholder="item"
              />
              {aliasError && (
                <div className="absolute top-full left-0 mt-1 text-xs text-red-600 whitespace-nowrap bg-white px-1 rounded shadow">
                  {aliasError}
                </div>
              )}
            </div>
          ) : (
            <code
              onClick={() => setIsEditingAlias(true)}
              className="px-2 py-0.5 text-sm bg-purple-100 rounded cursor-pointer hover:bg-purple-200 font-mono"
              title="Click to edit item variable name"
            >
              {block.itemAlias}
            </code>
          )}
        </div>

        {!isValidArray && (
          <div className="mt-1 text-xs text-red-600">⚠️ Expression must resolve to an array</div>
        )}

        {/* Preview Override Controls */}
        <div className="flex items-center gap-2 text-xs mt-2">
          <span className={isValidArray ? "text-purple-500" : "text-red-500"}>
            Items in data: {actualLength}
          </span>
          <span className="text-gray-400">|</span>
          <span className="text-gray-500">Preview count:</span>
          <input
            type="number"
            min="0"
            max="20"
            value={displayCount}
            onChange={(e) => {
              const val = parseInt(e.target.value, 10);
              setPreviewOverride("loops", block.id, isNaN(val) ? "data" : val);
            }}
            onClick={(e) => e.stopPropagation()}
            className="w-12 px-1 py-0.5 text-center bg-white border border-purple-300 rounded"
          />
          <button
            onClick={(e) => {
              e.stopPropagation();
              setPreviewOverride("loops", block.id, "data");
            }}
            className={`
              px-2 py-0.5 rounded transition-colors
              ${
                override === "data" || override === undefined
                  ? "bg-purple-500 text-white"
                  : "bg-purple-100 text-purple-700 hover:bg-purple-200"
              }
            `}
          >
            Reset
          </button>
        </div>
      </div>

      {/* Loop Content */}
      <div
        ref={setNodeRef}
        style={block.styles}
        className={`
          min-h-15 p-3
          ${isOver ? "bg-purple-100" : ""}
        `}
      >
        <ScopeProvider variables={scopeVariables}>
          {block.children.length === 0 ? (
            <div className="text-purple-400 text-sm">
              <p>Drop blocks here for loop template</p>
              <p className="text-xs mt-1">
                Use{" "}
                <code className="bg-purple-100 px-1 rounded">{`{{${block.itemAlias}.property}}`}</code>{" "}
                to access item properties
              </p>
            </div>
          ) : (
            <div className="space-y-2">
              {block.children.map((child, index) => (
                <BlockRenderer key={child.id} block={child} index={index} parentId={block.id} />
              ))}
            </div>
          )}
        </ScopeProvider>
      </div>

      {/* Preview hint */}
      <div className="px-3 py-1 border-t border-purple-200 bg-purple-100/50 text-xs text-purple-500">
        Preview will show {displayCount} iteration{displayCount !== 1 ? "s" : ""}
      </div>
    </div>
  );
}
