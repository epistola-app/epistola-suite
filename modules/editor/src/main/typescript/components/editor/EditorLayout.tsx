import { BlockPalette } from "./BlockPalette";
import { Canvas } from "./Canvas";
import { Preview } from "./Preview";
import { StyleSidebar } from "../styling";
import { useEditorStore } from "../../store/editorStore";
import { useEvaluator } from "../../context/EvaluatorContext";
import type { EvaluatorType } from "../../services/expression";
import type { Template } from "../../types/template";

interface EditorLayoutProps {
  /** When true, hides the internal header (for embedding in parent layout) */
  isEmbedded?: boolean;
  /** Callback when user clicks Save */
  onSave?: (template: Template) => void;
}

export function EditorLayout({ isEmbedded = false, onSave }: EditorLayoutProps) {
  const template = useEditorStore((s) => s.template);
  const templateName = template.name;
  const { type, setType, evaluator, isReady } = useEvaluator();

  const handleSave = () => {
    if (onSave) {
      onSave(template);
    }
  };

  return (
    <div className="h-full flex flex-col bg-gray-100">
      {/* Header - shown when not embedded */}
      {!isEmbedded && (
        <header className="h-14 bg-white border-b border-gray-200 flex items-center justify-between px-4 shrink-0">
          <h1 className="text-lg font-semibold text-gray-800">{templateName}</h1>
          <div className="flex items-center gap-4">
            {/* Evaluator Selector */}
            <div className="flex items-center gap-2">
              <span className="text-xs text-gray-500">Evaluator:</span>
              <select
                value={type}
                onChange={(e) => setType(e.target.value as EvaluatorType)}
                className="text-xs px-2 py-1 border border-gray-300 rounded bg-white"
              >
                <option value="direct">Direct (Fast)</option>
                <option value="iframe">Iframe (Sandboxed)</option>
              </select>
              <span
                className={`w-2 h-2 rounded-full ${isReady ? "bg-green-500" : "bg-yellow-500"}`}
                title={isReady ? "Ready" : "Initializing..."}
              />
              {evaluator.isSandboxed && (
                <span className="text-xs px-1.5 py-0.5 bg-green-100 text-green-700 rounded">
                  Secure
                </span>
              )}
            </div>
            <div className="flex gap-2">
              <button
                onClick={handleSave}
                className="px-3 py-1.5 text-sm bg-gray-100 hover:bg-gray-200 rounded-md transition-colors"
              >
                Save
              </button>
              <button className="px-3 py-1.5 text-sm bg-blue-600 text-white hover:bg-blue-700 rounded-md transition-colors">
                Export PDF
              </button>
            </div>
          </div>
        </header>
      )}

      {/* Toolbar - shown when embedded (minimal controls) */}
      {isEmbedded && (
        <div className="h-10 bg-white border-b border-gray-200 flex items-center justify-between px-4 shrink-0">
          <div className="flex items-center gap-2">
            <span className="text-xs text-gray-500">Evaluator:</span>
            <select
              value={type}
              onChange={(e) => setType(e.target.value as EvaluatorType)}
              className="text-xs px-2 py-1 border border-gray-300 rounded bg-white"
            >
              <option value="direct">Direct (Fast)</option>
              <option value="iframe">Iframe (Sandboxed)</option>
            </select>
            <span
              className={`w-2 h-2 rounded-full ${isReady ? "bg-green-500" : "bg-yellow-500"}`}
              title={isReady ? "Ready" : "Initializing..."}
            />
          </div>
          <div className="flex gap-2">
            <button
              onClick={handleSave}
              className="px-3 py-1.5 text-sm bg-blue-600 text-white hover:bg-blue-700 rounded-md transition-colors"
            >
              Save
            </button>
          </div>
        </div>
      )}

      {/* Main Content */}
      <div className="flex-1 flex overflow-hidden gap-2 p-2">
        {/* Style Sidebar - Left */}
        <StyleSidebar className="shrink-0 rounded-lg shadow-sm" />

        {/* Center Panel - Editor */}
        <div className="flex-1 flex flex-col bg-white min-w-0 rounded-lg shadow-sm overflow-hidden">
          {/* Block Palette */}
          <BlockPalette />

          {/* Canvas */}
          <div className="flex-1 overflow-auto">
            <Canvas />
          </div>
        </div>

        {/* Right Panel - Preview */}
        <div className="flex-1 bg-white overflow-auto min-w-0 rounded-lg shadow-sm">
          <Preview />
        </div>
      </div>
    </div>
  );
}
