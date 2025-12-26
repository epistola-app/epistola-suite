import { Switch } from "@/components/ui/animate-ui/components/radix/switch";
import { Label } from "@/components/ui/label";
import { useEditorStore, type Template } from "@/lib";
import { cn } from "@/lib/utils";
import { useDebounce, useLocalStorage } from "@uidotdev/usehooks";
import { Check, LoaderCircle, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";

const STORAGE_KEY = "epistola.editor.autoSaveEnabled.v1";
const DEBOUNCE_MS = 150;

type SaveStatus = "idle" | "saving" | "success" | "error";

interface AutoSaveProps {
  onSave: ((template: Template) => void | Promise<void>) | undefined;
}

export function AutoSave({ onSave }: AutoSaveProps) {
  const template = useEditorStore((s) => s.template);
  const debouncedTemplate = useDebounce(template, DEBOUNCE_MS);

  const [isEnabled, setIsEnabled] = useLocalStorage(STORAGE_KEY, false);
  const [status, setStatus] = useState<SaveStatus>("idle");

  const hasInitializedRef = useRef(false);

  // Auto-save when debounced template changes
  useEffect(() => {
    if (!hasInitializedRef.current) {
      hasInitializedRef.current = true;
      return;
    }

    if (!isEnabled || !onSave) return;

    const save = async () => {
      setStatus("saving");
      try {
        await onSave(debouncedTemplate);
        setStatus("success");
      } catch (error) {
        setStatus("error");
        console.error("Auto-save failed:", error);
      }
    };

    save();
  }, [debouncedTemplate, isEnabled, onSave]);

  // Reset status after delay
  useEffect(() => {
    if (status === "success" || status === "error") {
      const timer = setTimeout(() => setStatus("idle"), 2000);
      return () => clearTimeout(timer);
    }
  }, [status]);

  // Dynamic thumb icon based on status
  const thumbIcon = useMemo(() => {
    switch (status) {
      case "saving":
        return <LoaderCircle className="animate-spin text-muted-foreground" />;
      case "success":
        return <Check className="text-success" />;
      case "error":
        return <X className="text-destructive" />;
      default:
        return undefined;
    }
  }, [status]);

  return (
    <div className="flex items-center gap-1.5">
      <Switch
        id="auto-save"
        checked={isEnabled}
        onCheckedChange={setIsEnabled}
        thumbIcon={isEnabled ? thumbIcon : undefined}
        className={cn({
          "data-[state=checked]:bg-success": isEnabled && status !== "error",
          "data-[state=checked]:bg-destructive": isEnabled && status === "error",
        })}
      />
      <Label htmlFor="auto-save" className="text-xs cursor-pointer">
        Auto
      </Label>
    </div>
  );
}
