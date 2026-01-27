import { AlertCircle, AlertTriangle } from "lucide-react";
import { cn } from "@/lib/utils";

export interface ValidationError {
  path: string;
  message: string;
}

interface ValidationMessagesProps {
  errors?: ValidationError[];
  warnings?: ValidationError[];
  className?: string;
}

/**
 * Displays validation errors (red, blocking) and warnings (yellow, non-blocking)
 * in clearly separated sections.
 */
export function ValidationMessages({
  errors = [],
  warnings = [],
  className,
}: ValidationMessagesProps) {
  if (errors.length === 0 && warnings.length === 0) {
    return null;
  }

  return (
    <div className={cn("space-y-3", className)}>
      {errors.length > 0 && (
        <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3">
          <div className="flex items-center gap-2 text-sm font-medium text-destructive mb-2">
            <AlertCircle className="h-4 w-4" />
            <span>Errors ({errors.length})</span>
          </div>
          <ul className="space-y-1 text-xs text-destructive">
            {errors.map((error, i) => (
              <li key={i} className="flex gap-2">
                <code className="font-mono text-destructive/80">{error.path}</code>
                <span>{error.message}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {warnings.length > 0 && (
        <div className="rounded-md border border-amber-500/50 bg-amber-50 p-3">
          <div className="flex items-center gap-2 text-sm font-medium text-amber-700 mb-2">
            <AlertTriangle className="h-4 w-4" />
            <span>Warnings ({warnings.length})</span>
          </div>
          <ul className="space-y-1 text-xs text-amber-700">
            {warnings.map((warning, i) => (
              <li key={i} className="flex gap-2">
                <code className="font-mono text-amber-600">{warning.path}</code>
                <span>{warning.message}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

/**
 * Converts a map of example name -> errors into a flat list of errors with prefixed paths.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function flattenErrorsByExample(
  errorsByExample: Record<string, ValidationError[]>,
): ValidationError[] {
  const result: ValidationError[] = [];
  for (const [exampleName, errors] of Object.entries(errorsByExample)) {
    for (const error of errors) {
      result.push({
        path: `[${exampleName}] ${error.path}`,
        message: error.message,
      });
    }
  }
  return result;
}
