import { BlockPalette } from "./BlockPalette";
import { Canvas } from "./Canvas";
import { Preview } from "./Preview";
import { PdfPreview } from "./PdfPreview";
import { ExampleSelector } from "./ExampleSelector";
import { StyleSidebar } from "../styling";
import { useEditorStore, useIsDirty, type PreviewMode } from "../../store/editorStore";
import { useEvaluator } from "../../context/EvaluatorContext";
import type { EvaluatorType } from "../../services/expression";
import type { Template } from "../../types/template";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "../ui/select";
import SaveButton from "../ui/save-button";
import { Button } from "../ui/button";
import { ArrowLeftToLine } from "lucide-react";
import { Separator } from "../ui/separator";
import { useDefaultLayout } from "react-resizable-panels";
import { ResizableHandle, ResizablePanel, ResizablePanelGroup } from "../ui/resizable";
import { type ReactNode, useEffect } from "react";
import { AutoSave } from "../ui/auto-save";

interface EditorLayoutProps {
  /** When true, hides the internal header (for embedding in parent layout) */
  isEmbedded?: boolean;
  /** Callback when user clicks Save */
  onSave?: (template: Template) => void | Promise<void>;
  /** Callback when user selects a different example */
  onExampleSelected?: (exampleId: string | null) => void;
}

export function EditorLayout({
  isEmbedded = false,
  onSave,
  onExampleSelected,
}: EditorLayoutProps) {
  const template = useEditorStore((s) => s.template);
  const previewMode = useEditorStore((s) => s.previewMode);
  const isDirty = useIsDirty();

  // Warn user before leaving with unsaved changes
  useEffect(() => {
    const handleBeforeUnload = (e: BeforeUnloadEvent) => {
      if (isDirty) {
        e.preventDefault();
        e.returnValue = "";
      }
    };

    window.addEventListener("beforeunload", handleBeforeUnload);
    return () => window.removeEventListener("beforeunload", handleBeforeUnload);
  }, [isDirty]);

  return (
    <div className="h-screen flex flex-col bg-gray-100">
      <EditorHeader
        isEmbedded={isEmbedded}
        onSave={onSave}
        onExampleSelected={onExampleSelected}
        template={template}
      />
      {/* Main Content */}
      <div className="flex-1 flex overflow-hidden gap-3 p-3">
        <StyleSidebar className="shrink-0 rounded-xl shadow-lg border border-slate-200/50" />

        <ResizableContent
          leftSide={
            <>
              <BlockPalette />
              <div className="flex-1 overflow-auto bg-slate-50">
                <Canvas />
              </div>
            </>
          }
          rightSide={previewMode === "pdf" ? <PdfPreview /> : <Preview />}
        />
      </div>
    </div>
  );
}

function EvaluatorSelector() {
  const { type, setType, isReady } = useEvaluator();
  return (
    <div className="flex items-center gap-3">
      <span className="text-xs font-medium text-slate-600">Evaluator:</span>
      <Select value={type} onValueChange={(value) => setType(value as EvaluatorType)}>
        <SelectTrigger
          size="sm"
          className="text-xs px-2 py-1 border border-slate-200 rounded-md bg-white hover:border-slate-300 transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-500 w-40 h-7"
        >
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="direct">Direct (Fast)</SelectItem>
          <SelectItem value="iframe">Iframe (Secure)</SelectItem>
        </SelectContent>
      </Select>
      <span
        className={`w-1.5 h-1.5 rounded-full transition-colors ${
          isReady ? "bg-emerald-500" : "bg-amber-500 animate-pulse"
        }`}
        title={isReady ? "Ready" : "Initializing..."}
      />
    </div>
  );
}

function PreviewModeSelector() {
  const previewMode = useEditorStore((s) => s.previewMode);
  const setPreviewMode = useEditorStore((s) => s.setPreviewMode);

  return (
    <div className="flex items-center gap-3">
      <span className="text-xs font-medium text-slate-600">Preview:</span>
      <Select value={previewMode} onValueChange={(value) => setPreviewMode(value as PreviewMode)}>
        <SelectTrigger
          size="sm"
          className="text-xs px-2 py-1 border border-slate-200 rounded-md bg-white hover:border-slate-300 transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-500 w-32 h-7"
        >
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="html">HTML (Fast)</SelectItem>
          <SelectItem value="pdf">PDF (Actual)</SelectItem>
        </SelectContent>
      </Select>
    </div>
  );
}

interface EditorHeaderProps {
  isEmbedded: boolean;
  onSave: ((template: Template) => void | Promise<void>) | undefined;
  onExampleSelected: ((exampleId: string | null) => void) | undefined;
  template: Template;
}

function EditorHeader({
  isEmbedded,
  onSave,
  onExampleSelected,
  template,
}: EditorHeaderProps) {
  const dataExamples = useEditorStore((s) => s.dataExamples);
  const selectedDataExampleId = useEditorStore((s) => s.selectedDataExampleId);
  const selectDataExample = useEditorStore((s) => s.selectDataExample);
  const schema = useEditorStore((s) => s.schema);

  const handleSave = () => {
    if (onSave) {
      onSave(template);
    }
  };

  const handleExampleSelect = (exampleId: string | null) => {
    selectDataExample(exampleId);
    if (onExampleSelected) {
      onExampleSelected(exampleId);
    }
  };

  return (
    <>
      {!isEmbedded && (
        <header className="h-14 bg-white border-b border-gray-200 flex items-center justify-between px-4 shrink-0">
          <h1 className="text-lg font-semibold text-gray-800">{template.name}</h1>
          <div className="flex items-center gap-4">
            {/* Example Selector */}
            <ExampleSelector
              examples={dataExamples}
              selectedId={selectedDataExampleId}
              schema={schema}
              onSelect={handleExampleSelect}
            />
            <Separator orientation="vertical" className="min-h-6" />
            {/* Preview Mode Selector */}
            <PreviewModeSelector />
            <Separator orientation="vertical" className="min-h-6" />
            {/* Evaluator Selector */}
            <EvaluatorSelector />

            <div className="flex gap-2">
              <button
                onClick={handleSave}
                className="px-3 py-1.5 text-sm bg-gray-100 hover:bg-gray-200 rounded-md transition-colors"
              >
                Save
              </button>
              <button className="px-3 py-1.5 text-sm bg-emerald-600 text-white hover:bg-emerald-700 rounded-md transition-colors">
                Export PDF
              </button>
            </div>
          </div>
        </header>
      )}

      {/* Toolbar - shown when embedded (minimal controls) */}
      {isEmbedded && (
        <div className="h-12 bg-white border-b border-gray-200 flex items-center justify-between px-4 py-2">
          <div className="flex items-center gap-4">
            <Button
              variant="ghost"
              size="sm"
              className="text-sm font-normal text-foreground"
              asChild
            >
              <a
                href={`/tenants/${window.TENANT_ID}/templates`}
                className="flex items-center gap-2"
              >
                <ArrowLeftToLine className="size-4 shrink-0" />
                <span>Back to Templates</span>
              </a>
            </Button>
            <Separator orientation="vertical" className="min-h-6" />
            {/* TODO: Make template name editable with proper save state tracking */}
            <span className="text-sm font-medium text-foreground">{template.name}</span>
          </div>
          <div className="flex items-center gap-4">
            <ExampleSelector
              examples={dataExamples}
              selectedId={selectedDataExampleId}
              schema={schema}
              onSelect={handleExampleSelect}
            />
            <Separator orientation="vertical" className="min-h-6" />
            <PreviewModeSelector />
            <Separator orientation="vertical" className="min-h-6" />
            <EvaluatorSelector />
            <Separator orientation="vertical" className="min-h-6" />
            <AutoSave onSave={onSave} />
            <SaveButton onSave={onSave} />
          </div>
        </div>
      )}
    </>
  );
}

interface ResizableContentProps {
  leftSide: ReactNode;
  rightSide: ReactNode;
}

function ResizableContent({ leftSide, rightSide }: ResizableContentProps) {
  const { defaultLayout, onLayoutChange } = useDefaultLayout({
    id: "editor-preview-layout",
    storage: localStorage,
  });

  return (
    <ResizablePanelGroup
      orientation="horizontal"
      className="flex-1 gap-3"
      defaultLayout={defaultLayout}
      onLayoutChange={onLayoutChange}
    >
      <ResizablePanel
        defaultSize="50vw"
        minSize="30vw"
        maxSize="100vw"
        collapsible
        collapsedSize={0}
        className="flex flex-col bg-white min-w-0 rounded-xl shadow-lg overflow-hidden border border-slate-200/50"
      >
        {leftSide}
      </ResizablePanel>

      <ResizableHandle withHandle />

      <ResizablePanel
        defaultSize="50vw"
        minSize="30vw"
        maxSize="100vw"
        collapsible
        collapsedSize={0}
        className="bg-white overflow-auto min-w-0 rounded-xl shadow-lg border border-slate-200/50"
      >
        {rightSide}
      </ResizablePanel>
    </ResizablePanelGroup>
  );
}
