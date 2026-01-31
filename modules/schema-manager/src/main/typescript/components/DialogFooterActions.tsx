import {Circle} from "lucide-react";
import {Button} from "@/components/ui/button";
import {DialogFooter} from "@/components/ui/dialog";
import {cn} from "@/lib/utils";

interface DialogFooterActionsProps {
  isDirty: boolean;
  isSaving: boolean;
  onSave: () => void;
  onClose: () => void;
  saveLabel?: string;
  savingLabel?: string;
  closeLabel?: string;
  disabled?: boolean;
  className?: string;
}

/**
 * Reusable dialog footer with Save, Close buttons and unsaved changes indicator.
 * Implements the "Save & Stay Open" pattern.
 */
export function DialogFooterActions({
  isDirty,
  isSaving,
  onSave,
  onClose,
  saveLabel = "Save",
  savingLabel = "Saving...",
  closeLabel = "Close",
  disabled = false,
  className,
}: DialogFooterActionsProps) {
  return (
    <DialogFooter className={cn("flex items-center", className)}>
      {/* Unsaved changes indicator */}
      {isDirty && (
        <div className="flex items-center gap-1.5 text-xs text-amber-600 mr-auto">
          <Circle className="h-2 w-2 fill-amber-500" />
          <span>Unsaved changes</span>
        </div>
      )}

      <Button variant="outline" onClick={onClose} disabled={isSaving}>
        {closeLabel}
      </Button>
      <Button onClick={onSave} disabled={disabled || isSaving || !isDirty}>
        {isSaving ? savingLabel : saveLabel}
      </Button>
    </DialogFooter>
  );
}

interface ConfirmCloseDialogProps {
  open: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * Simple confirmation dialog when closing with unsaved changes.
 * Can be used inline or as a separate dialog.
 */
export function ConfirmClosePrompt({ open, onConfirm, onCancel }: ConfirmCloseDialogProps) {
  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="bg-white rounded-lg shadow-lg p-6 max-w-sm mx-4">
        <h3 className="text-lg font-semibold mb-2">Unsaved Changes</h3>
        <p className="text-sm text-muted-foreground mb-4">
          You have unsaved changes. Are you sure you want to close?
        </p>
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onCancel}>
            Cancel
          </Button>
          <Button variant="destructive" onClick={onConfirm}>
            Discard Changes
          </Button>
        </div>
      </div>
    </div>
  );
}
