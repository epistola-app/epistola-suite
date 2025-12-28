import * as React from "react";
import { RotateCcw } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "./button";
import {
  ColorPicker,
  ColorPickerAlphaSlider,
  ColorPickerArea,
  ColorPickerContent,
  ColorPickerEyeDropper,
  ColorPickerFormatSelect,
  ColorPickerHueSlider,
  ColorPickerInput,
  ColorPickerSwatch,
  ColorPickerTrigger,
} from "./color-picker";

interface ColorInputProps {
  value?: string;
  defaultValue?: string;
  onChange: (value: string | undefined) => void;
  showReset?: boolean;
  className?: string;
  disabled?: boolean;
  id?: string;
}

function ColorInput({
  value,
  defaultValue = "#000000",
  onChange,
  showReset = true,
  className,
  disabled,
  id,
}: ColorInputProps) {
  const displayValue = value ?? defaultValue;
  const currentValueRef = React.useRef(displayValue);

  // Track current value without triggering re-renders
  const handleValueChange = React.useCallback((newValue: string) => {
    currentValueRef.current = newValue;
  }, []);

  // Commit value when popover closes
  const handleOpenChange = React.useCallback(
    (open: boolean) => {
      if (!open) {
        onChange(currentValueRef.current);
      }
    },
    [onChange],
  );

  // Handle reset
  const handleReset = React.useCallback(() => {
    currentValueRef.current = defaultValue;
    onChange(undefined);
  }, [onChange, defaultValue]);

  return (
    <div className={cn("flex items-center gap-2", className)}>
      <ColorPicker
        defaultValue={displayValue}
        onValueChange={handleValueChange}
        onOpenChange={handleOpenChange}
        defaultFormat="hex"
        disabled={disabled}
        className="w-full"
      >
        <ColorPickerTrigger asChild>
          <Button
            id={id}
            variant="outline"
            disabled={disabled}
            className="flex items-center w-full gap-2 h-8 flex-1"
          >
            <ColorPickerSwatch className="size-4" />
            <span className="text-xs font-normal text-muted-foreground">{displayValue}</span>
          </Button>
        </ColorPickerTrigger>
        <ColorPickerContent>
          <ColorPickerArea />
          <div className="flex items-center gap-2">
            <ColorPickerEyeDropper />
            <div className="flex flex-1 flex-col gap-2">
              <ColorPickerHueSlider />
              <ColorPickerAlphaSlider />
            </div>
          </div>
          <div className="flex items-center gap-2">
            <ColorPickerFormatSelect />
            <ColorPickerInput />
          </div>
        </ColorPickerContent>
      </ColorPicker>
      {showReset && (
        <Button
          variant="outline"
          size="sm"
          onClick={handleReset}
          disabled={disabled}
          className="h-8 w-8 p-0 shrink-0"
          title="Reset to default"
        >
          <RotateCcw className="size-3.5" />
        </Button>
      )}
    </div>
  );
}

export { ColorInput };
export type { ColorInputProps };
