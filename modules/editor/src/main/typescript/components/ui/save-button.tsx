import { Button } from "@/components/ui/button";
import { useEditorStore, type Template } from "@/lib";
import { cn } from "@/lib/utils";
import { CircleAlert, CircleCheck, LoaderCircleIcon, Save } from "lucide-react";
import { useCallback, useEffect, useState } from "react";

interface SaveButtonProps {
  onSave: ((template: Template) => void | Promise<void>) | undefined;
}
const StatusMessages = {
  success: "Saved!" as const,
  error: "Failed!" as const,
};
type StatusMessages = (typeof StatusMessages)[keyof typeof StatusMessages];

export default function SaveButton({ onSave }: SaveButtonProps) {
  const template = useEditorStore((s) => s.template);
  const [isLoading, setIsLoading] = useState(false);
  const [status, setStatus] = useState<StatusMessages | undefined>(undefined);

  const handleClick = useCallback(async () => {
    setIsLoading(true);
    setStatus(undefined);
    try {
      if (onSave) {
        await onSave(template);
        setStatus(StatusMessages.success);
      }
    } catch (error) {
      setStatus(StatusMessages.error);
      console.error(error);
    } finally {
      setIsLoading(false);
    }
  }, [onSave, template]);

  // Ctrl + S shortcut for saving
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.ctrlKey && event.key === "s") {
        event.preventDefault();
        handleClick();
      }
    };
    window.addEventListener("keydown", handleKeyDown);

    return () => {
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [handleClick]);

  // Reset status after 2 seconds
  // This will make the button go back to its normal/original state
  useEffect(() => {
    if (status) {
      const timer = setTimeout(() => {
        setStatus(undefined);
      }, 2000);

      return () => clearTimeout(timer);
    }
  }, [status]);

  // prettier-ignore
  return (
    <Button
      variant="outline"
      size="sm"
      onClick={handleClick}
      disabled={isLoading}
      className={cn("cursor-pointer w-28 font-normal", {
        "text-success hover:text-success border-success/50": status === "Saved!",
        "text-destructive hover:text-destructive border-destructive/50": status === "Failed!",
      })}
    >
      {isLoading ? (
        <>
          <LoaderCircleIcon className="animate-spin" />
          Loading
        </>
      ) : !status ? (
        <span className="flex items-center gap-2">
          <Save className="size-4 shrink-0" />
          Save
        </span>
      ) : status === "Saved!" ? (
        <span className="flex items-center gap-2">
          <CircleCheck className="size-4 shrink-0" />
          {StatusMessages.success}
        </span>
      ) : status === "Failed!" ? (
        <span className="flex items-center gap-2">
          <CircleAlert className="size-4 shrink-0" />
          {StatusMessages.error}
        </span>
      ) : null}
    </Button>
  );
}
