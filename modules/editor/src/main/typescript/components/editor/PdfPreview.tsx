import { useState, useEffect, useRef, useCallback } from "react";
import { useEditorStore } from "../../store/editorStore";
import { Loader2, AlertCircle, RefreshCw } from "lucide-react";
import { Button } from "../ui/button";

type PdfState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "success"; blobUrl: string }
  | { status: "error"; message: string };

export function PdfPreview() {
  const template = useEditorStore((s) => s.template);
  const testData = useEditorStore((s) => s.testData);
  const [pdfState, setPdfState] = useState<PdfState>({ status: "idle" });
  const abortControllerRef = useRef<AbortController | null>(null);
  const currentBlobUrlRef = useRef<string | null>(null);

  const fetchPdf = useCallback(async () => {
    const tenantId = window.TENANT_ID;
    const templateId = window.TEMPLATE_ID;
    const variantId = window.VARIANT_ID;

    if (!tenantId || !templateId || !variantId) {
      setPdfState({
        status: "error",
        message: "Missing tenant, template, or variant ID",
      });
      return;
    }

    // Abort any in-flight request
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
    }
    abortControllerRef.current = new AbortController();

    // Revoke previous blob URL to free memory
    if (currentBlobUrlRef.current) {
      URL.revokeObjectURL(currentBlobUrlRef.current);
      currentBlobUrlRef.current = null;
    }

    setPdfState({ status: "loading" });

    try {
      // Send both the current template model and test data for live preview
      const requestBody = {
        templateModel: template,
        data: testData,
      };

      const response = await fetch(
        `/tenants/${tenantId}/templates/${templateId}/variants/${variantId}/preview`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify(requestBody),
          signal: abortControllerRef.current.signal,
        },
      );

      if (!response.ok) {
        const errorText = await response.text().catch(() => response.statusText);
        throw new Error(errorText || `HTTP ${response.status}`);
      }

      const blob = await response.blob();
      const blobUrl = URL.createObjectURL(blob);
      currentBlobUrlRef.current = blobUrl;

      setPdfState({ status: "success", blobUrl });
    } catch (error) {
      if (error instanceof Error && error.name === "AbortError") {
        // Request was aborted, don't update state
        return;
      }
      setPdfState({
        status: "error",
        message: error instanceof Error ? error.message : "Failed to fetch PDF",
      });
    }
  }, [template, testData]);

  // Debounced fetch when template or data changes
  useEffect(() => {
    const timer = setTimeout(() => {
      fetchPdf();
    }, 500);

    return () => {
      clearTimeout(timer);
    };
  }, [fetchPdf]);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }
      if (currentBlobUrlRef.current) {
        URL.revokeObjectURL(currentBlobUrlRef.current);
      }
    };
  }, []);

  return (
    <div className="h-full p-4">
      <div className="bg-white shadow-lg rounded-lg overflow-hidden h-full flex flex-col">
        <div className="bg-gray-100 px-4 py-2 border-b border-gray-200 flex items-center justify-between">
          <span className="text-sm font-medium text-gray-600">PDF Preview</span>
          <div className="flex items-center gap-2">
            {pdfState.status === "loading" && (
              <Loader2 className="h-4 w-4 animate-spin text-gray-400" />
            )}
            <span className="text-xs text-gray-400">
              {template.pageSettings.format} {template.pageSettings.orientation}
            </span>
          </div>
        </div>
        <div className="flex-1 overflow-auto bg-gray-50 p-4">
          {pdfState.status === "idle" && (
            <div className="flex items-center justify-center text-gray-400 w-full h-full">
              <p>Loading...</p>
            </div>
          )}

          {pdfState.status === "loading" && (
            <div className="flex flex-col items-center justify-center gap-3 text-gray-500 w-full h-full">
              <Loader2 className="h-8 w-8 animate-spin" />
              <p className="text-sm">Generating PDF...</p>
            </div>
          )}

          {pdfState.status === "error" && (
            <div className="flex flex-col items-center justify-center gap-4 p-8 w-full h-full">
              <AlertCircle className="h-12 w-12 text-red-400" />
              <div className="text-center">
                <p className="text-sm font-medium text-gray-700">Failed to generate PDF</p>
                <p className="text-xs text-gray-500 mt-1 max-w-md">{pdfState.message}</p>
              </div>
              <Button variant="outline" size="sm" onClick={fetchPdf} className="gap-2">
                <RefreshCw className="h-4 w-4" />
                Retry
              </Button>
            </div>
          )}

          {pdfState.status === "success" && (
            <div className="w-full h-full flex justify-center">
              <iframe
                src={pdfState.blobUrl}
                className="border-0 shadow-lg h-full"
                style={{ width: "100%", maxWidth: "210mm" }}
                title="PDF Preview"
              />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
