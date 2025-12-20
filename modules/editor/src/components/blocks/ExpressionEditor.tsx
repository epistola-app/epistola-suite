import { useState, useRef, useEffect, useMemo } from 'react';
import { useEditorStore } from '../../store/editorStore';
import { useScope } from '../../context/ScopeContext';
import { useEvaluator } from '../../context/EvaluatorContext';
import type { ScopeVariable } from '../../context/ScopeContext';

interface ExpressionEditorProps {
  value: string;
  onSave: (value: string) => void;
  onCancel: () => void;
  filterArraysOnly?: boolean;
}

interface PathInfo {
  path: string;
  isArray: boolean;
  type: string;
}

// Extract all possible paths from an object with type info
function extractPaths(obj: unknown, prefix = ''): PathInfo[] {
  const paths: PathInfo[] = [];

  if (obj === null || obj === undefined) {
    return paths;
  }

  if (Array.isArray(obj)) {
    // For arrays, show the array path and item paths with [0]
    paths.push({ path: prefix, isArray: true, type: `array[${obj.length}]` });
    if (obj.length > 0) {
      const itemPaths = extractPaths(obj[0], `${prefix}[0]`);
      paths.push(...itemPaths);
      // Also suggest .length, .map, .filter, etc.
      paths.push({ path: `${prefix}.length`, isArray: false, type: 'number' });
    }
  } else if (typeof obj === 'object') {
    for (const [key, value] of Object.entries(obj as Record<string, unknown>)) {
      const path = prefix ? `${prefix}.${key}` : key;
      const isArray = Array.isArray(value);
      const type = isArray ? `array[${value.length}]` : typeof value;
      paths.push({ path, isArray, type });
      paths.push(...extractPaths(value, path));
    }
  }

  return paths;
}

// Resolve scope variable to actual value from test data
export function resolveScopeVariable(
  varName: string,
  scopeVars: ScopeVariable[],
  testData: Record<string, unknown>
): unknown | undefined {
  const scopeVar = scopeVars.find(v => v.name === varName);
  if (!scopeVar) return undefined;

  if (scopeVar.type === 'loop-index') {
    return 0; // Preview with index 0
  }

  if (scopeVar.type === 'loop-item') {
    // Get the first item from the array
    const arrayPath = scopeVar.arrayPath.split('.');
    let arrayValue: unknown = testData;
    for (const part of arrayPath) {
      arrayValue = (arrayValue as Record<string, unknown>)?.[part];
    }
    if (Array.isArray(arrayValue) && arrayValue.length > 0) {
      return arrayValue[0];
    }
  }

  return undefined;
}

// Build evaluation context with scope variables resolved
export function buildEvaluationContext(
  data: Record<string, unknown>,
  scopeVars: ScopeVariable[]
): Record<string, unknown> {
  const context: Record<string, unknown> = { ...data };
  for (const scopeVar of scopeVars) {
    const resolved = resolveScopeVariable(scopeVar.name, scopeVars, data);
    if (resolved !== undefined) {
      context[scopeVar.name] = resolved;
    }
  }
  return context;
}

export function ExpressionEditor({ value, onSave, onCancel, filterArraysOnly = false }: ExpressionEditorProps) {
  const [inputValue, setInputValue] = useState(value);
  const [showSuggestions, setShowSuggestions] = useState(true);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [validation, setValidation] = useState<{ valid: boolean; error?: string; value?: unknown; loading?: boolean }>({ valid: false, loading: true });
  const inputRef = useRef<HTMLInputElement>(null);
  const testData = useEditorStore((s) => s.testData);
  const scope = useScope();
  const { evaluate, isReady } = useEvaluator();

  // Extract all available paths from test data, including scope variables
  const allPaths = useMemo(() => {
    const paths = extractPaths(testData);

    // Add scope variables (from loops, etc.)
    for (const scopeVar of scope.variables) {
      if (scopeVar.type === 'loop-item') {
        // Get the array from test data to extract item properties
        const arrayPath = scopeVar.arrayPath.split('.');
        let arrayValue: unknown = testData;
        for (const part of arrayPath) {
          arrayValue = (arrayValue as Record<string, unknown>)?.[part];
        }

        if (Array.isArray(arrayValue) && arrayValue.length > 0) {
          // Add the item variable itself
          const itemSample = arrayValue[0];
          const itemType = typeof itemSample === 'object' ? 'object' : typeof itemSample;
          paths.unshift({
            path: scopeVar.name,
            isArray: false,
            type: `${itemType} (loop item)`,
          });

          // Add properties of the item
          if (typeof itemSample === 'object' && itemSample !== null) {
            const itemPaths = extractPaths(itemSample, scopeVar.name);
            // Add these at the beginning so they appear first
            paths.unshift(...itemPaths.map(p => ({
              ...p,
              type: `${p.type} (from ${scopeVar.arrayPath})`,
            })));
          }
        } else {
          // Array not found or empty, still add the variable
          paths.unshift({
            path: scopeVar.name,
            isArray: false,
            type: 'loop item',
          });
        }
      } else if (scopeVar.type === 'loop-index') {
        paths.unshift({
          path: scopeVar.name,
          isArray: false,
          type: 'number (loop index)',
        });
      }
    }

    if (filterArraysOnly) {
      return paths.filter(p => p.isArray);
    }
    return paths;
  }, [testData, filterArraysOnly, scope.variables]);

  // Filter suggestions based on input
  const suggestions = useMemo(() => {
    if (!inputValue.trim()) return allPaths.slice(0, 10);

    const lower = inputValue.toLowerCase();
    return allPaths
      .filter(p => p.path.toLowerCase().includes(lower))
      .slice(0, 10);
  }, [inputValue, allPaths]);

  // Async validation with debouncing
  useEffect(() => {
    if (!isReady) {
      setValidation({ valid: false, error: 'Evaluator not ready' });
      return;
    }
    const trimmed = inputValue.trim();
    if (!trimmed) {
      setValidation({ valid: false, error: 'Expression cannot be empty' });
      return;
    }

    // Set loading state
    setValidation({ valid: false, loading: true });

    const context = buildEvaluationContext(testData, scope.variables);
    let cancelled = false;

    // Small debounce for rapid typing
    const timer = setTimeout(() => {
      evaluate(trimmed, context).then((result) => {
        if (cancelled) return;
        if (result.success) {
          setValidation({ valid: true, value: result.value });
        } else {
          setValidation({ valid: false, error: result.error });
        }
      });
    }, 50);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [inputValue, testData, scope.variables, evaluate, isReady]);

  useEffect(() => {
    // Small delay to ensure focus after Tiptap finishes its operations
    const timer = setTimeout(() => {
      inputRef.current?.focus();
      inputRef.current?.select();
    }, 10);
    return () => clearTimeout(timer);
  }, []);

  useEffect(() => {
    setSelectedIndex(0);
  }, [suggestions]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      if (showSuggestions && suggestions.length > 0 && selectedIndex < suggestions.length) {
        setInputValue(suggestions[selectedIndex].path);
        setShowSuggestions(false);
      } else {
        onSave(inputValue);
      }
    } else if (e.key === 'Escape') {
      onCancel();
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      setSelectedIndex(i => Math.min(i + 1, suggestions.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setSelectedIndex(i => Math.max(i - 1, 0));
    } else if (e.key === 'Tab' && suggestions.length > 0) {
      e.preventDefault();
      setInputValue(suggestions[selectedIndex].path);
      setShowSuggestions(false);
    }
  };

  const handleSuggestionClick = (suggestion: PathInfo) => {
    setInputValue(suggestion.path);
    setShowSuggestions(false);
    inputRef.current?.focus();
  };

  return (
    <div className="relative">
      {/* Input with validation indicator */}
      <div className="flex items-center gap-2">
        <div className="relative flex-1">
          <input
            ref={inputRef}
            type="text"
            value={inputValue}
            onChange={(e) => {
              setInputValue(e.target.value);
              setShowSuggestions(true);
            }}
            onKeyDown={handleKeyDown}
            onBlur={() => {
              // Delay to allow click on suggestions
              setTimeout(() => setShowSuggestions(false), 150);
            }}
            onFocus={() => setShowSuggestions(true)}
            className={`
              w-full px-3 py-1.5 text-sm font-mono
              border rounded-md shadow-lg
              focus:outline-none focus:ring-2
              ${'loading' in validation && validation.loading
                ? 'border-gray-300 focus:ring-gray-300 bg-gray-50'
                : validation.valid
                  ? 'border-green-400 focus:ring-green-300 bg-green-50'
                  : 'border-red-400 focus:ring-red-300 bg-red-50'}
            `}
            placeholder="customer.name"
            style={{ minWidth: '250px' }}
          />

          {/* Validation indicator */}
          <span className={`absolute right-2 top-1/2 -translate-y-1/2 text-sm ${
            'loading' in validation && validation.loading
              ? 'text-gray-400'
              : validation.valid ? 'text-green-600' : 'text-red-600'
          }`}>
            {'loading' in validation && validation.loading ? '...' : validation.valid ? '✓' : '✗'}
          </span>
        </div>

        {/* Save/Cancel buttons */}
        <button
          onClick={() => onSave(inputValue)}
          className="px-2 py-1 text-xs bg-blue-500 text-white rounded hover:bg-blue-600"
        >
          Save
        </button>
        <button
          onClick={onCancel}
          className="px-2 py-1 text-xs bg-gray-300 text-gray-700 rounded hover:bg-gray-400"
        >
          Cancel
        </button>
      </div>

      {/* Error message */}
      {!validation.valid && validation.error && (
        <div className="mt-1 text-xs text-red-600">
          {validation.error}
        </div>
      )}

      {/* Autocomplete suggestions */}
      {showSuggestions && suggestions.length > 0 && (
        <div className="absolute z-50 mt-1 w-full bg-white border border-gray-200 rounded-md shadow-lg max-h-48 overflow-auto">
          {suggestions.map((suggestion, index) => (
            <div
              key={suggestion.path}
              onClick={() => handleSuggestionClick(suggestion)}
              className={`
                px-3 py-1.5 text-sm cursor-pointer flex justify-between items-center
                ${index === selectedIndex ? 'bg-blue-100 text-blue-800' : 'hover:bg-gray-100'}
              `}
            >
              <span className="font-mono">{suggestion.path}</span>
              <span className={`text-xs ml-2 px-1.5 py-0.5 rounded ${
                suggestion.isArray
                  ? 'bg-purple-100 text-purple-700'
                  : 'bg-gray-100 text-gray-500'
              }`}>
                {suggestion.type}
              </span>
            </div>
          ))}
          <div className="px-3 py-1 text-xs text-gray-400 border-t border-gray-100">
            ↑↓ navigate • Tab/Enter select • Esc cancel
          </div>
        </div>
      )}

      {/* Preview of evaluated value */}
      {'loading' in validation && validation.loading ? (
        <div className="mt-1 text-xs text-gray-400">
          Evaluating...
        </div>
      ) : validation.valid && (
        <div className="mt-1 text-xs text-gray-500">
          Preview: <span className="font-mono text-gray-700">{formatPreviewValue(validation.value)}</span>
        </div>
      )}
    </div>
  );
}

function formatPreviewValue(value: unknown): string {
  if (value === undefined) return 'undefined';
  if (value === null) return 'null';
  if (typeof value === 'object') {
    const json = JSON.stringify(value);
    return json.length > 50 ? json.slice(0, 50) + '...' : json;
  }
  return String(value);
}
