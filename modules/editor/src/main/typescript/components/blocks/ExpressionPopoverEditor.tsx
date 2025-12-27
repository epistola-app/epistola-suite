import { useState, useEffect, useMemo, useCallback } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { javascript } from "@codemirror/lang-javascript";
import { autocompletion } from "@codemirror/autocomplete";
import { keymap, tooltips, EditorView } from "@codemirror/view";
import { Prec, EditorSelection } from "@codemirror/state";
import { Check, X, Loader2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { useEditorStore } from "../../store/editorStore";
import { useScope } from "../../context/ScopeContext";
import { useEvaluator } from "../../context/EvaluatorContext";
import { useExpressionCompletion } from "../../hooks/use-expression-completion";
import { expressionTheme } from "../../lib/codemirror/expression-theme";
import { buildEvaluationContext, formatPreviewValue } from "@/lib/expression-utils";

interface ExpressionPopoverEditorProps {
  value: string;
  onSave: (value: string) => void;
  onCancel: () => void;
  filterArraysOnly?: boolean;
}

type ValidationState =
  | { valid: true; value: unknown }
  | { valid: false; error: string }
  | { valid: false; loading: true };

export function ExpressionPopoverEditor({ value, onSave, onCancel }: ExpressionPopoverEditorProps) {
  const [inputValue, setInputValue] = useState(value);
  const [validation, setValidation] = useState<ValidationState>({ valid: false, loading: true });

  const testData = useEditorStore((s) => s.testData);
  const scope = useScope();
  const { evaluate, isReady } = useEvaluator();

  // Build completion source
  const completionSource = useExpressionCompletion({
    testData,
    scopeVars: scope.variables,
  });

  // Handle save action
  const handleSave = useCallback(() => {
    onSave(inputValue.trim());
  }, [inputValue, onSave]);

  // Set cursor to end of content when editor is created
  const handleCreateEditor = useCallback((view: EditorView) => {
    const length = view.state.doc.length;
    view.dispatch({
      selection: EditorSelection.cursor(length),
    });
  }, []);

  // CodeMirror extensions
  const extensions = useMemo(
    () => [
      javascript(),
      autocompletion({
        override: [completionSource],
        activateOnTyping: true,
        defaultKeymap: true,
      }),
      // Render tooltips (autocomplete) in document.body to prevent clipping
      tooltips({ parent: document.body }),
      Prec.highest(
        keymap.of([
          {
            key: "Escape",
            run: () => {
              onCancel();
              return true;
            },
          },
          {
            key: "Mod-Enter",
            run: () => {
              handleSave();
              return true;
            },
          },
        ]),
      ),
      ...expressionTheme,
    ],
    [completionSource, onCancel, handleSave],
  );

  // Debounced validation
  useEffect(() => {
    if (!isReady) {
      setValidation({ valid: false, loading: true });
      return;
    }

    const trimmed = inputValue.trim();
    if (!trimmed) {
      setValidation({ valid: false, error: "Expression is empty" });
      return;
    }

    setValidation({ valid: false, loading: true });

    const context = buildEvaluationContext(testData, scope.variables);
    const timer = setTimeout(() => {
      evaluate(trimmed, context).then((result) => {
        if (result.success) {
          setValidation({ valid: true, value: result.value });
        } else {
          setValidation({ valid: false, error: result.error ?? "Evaluation failed" });
        }
      });
    }, 50);

    return () => clearTimeout(timer);
  }, [inputValue, testData, scope.variables, evaluate, isReady]);

  const isLoading = "loading" in validation && validation.loading;

  return (
    <div className="w-96 space-y-3">
      {/* CodeMirror Editor */}
      <div
        className={cn(
          "rounded-md border overflow-hidden transition-colors",
          isLoading && "border-muted-foreground/30",
          !isLoading && validation.valid && "border-green-500",
          !isLoading && !validation.valid && "border-destructive",
        )}
      >
        <CodeMirror
          value={inputValue}
          onChange={setInputValue}
          extensions={extensions}
          height="auto"
          minHeight="60px"
          maxHeight="200px"
          autoFocus
          onCreateEditor={handleCreateEditor}
          basicSetup={{
            lineNumbers: false,
            foldGutter: false,
            highlightActiveLine: false,
            highlightActiveLineGutter: false,
            bracketMatching: true,
            closeBrackets: true,
            autocompletion: false, // We use our own
          }}
        />
      </div>

      {/* Validation Status */}
      <div className="min-h-6 flex items-center text-xs">
        {isLoading ? (
          <span className="text-muted-foreground flex items-center gap-1.5">
            <Loader2 className="size-3 animate-spin" />
            Evaluating...
          </span>
        ) : validation.valid ? (
          <span className="text-muted-foreground">
            <span className="text-green-600 font-medium flex items-center gap-1 inline-flex">
              <Check className="size-3" />
              Preview:
            </span>{" "}
            <code className="text-foreground bg-muted px-1.5 py-0.5 rounded text-[11px]">
              {formatPreviewValue(validation.value)}
            </code>
          </span>
        ) : "error" in validation ? (
          <span className="text-destructive flex items-center gap-1">
            <X className="size-3" />
            {validation.error}
          </span>
        ) : null}
      </div>

      {/* Actions */}
      <div className="flex items-center justify-between">
        <div className="text-[10px] text-muted-foreground space-x-2">
          <span>
            <kbd className="px-1 py-0.5 bg-muted rounded">Ctrl+Enter</kbd> save
          </span>
          <span>
            <kbd className="px-1 py-0.5 bg-muted rounded">Esc</kbd> cancel
          </span>
        </div>
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={onCancel}>
            Cancel
          </Button>
          <Button size="sm" onClick={handleSave}>
            Save
          </Button>
        </div>
      </div>
    </div>
  );
}
