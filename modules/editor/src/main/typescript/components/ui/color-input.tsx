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
  debounceMs?: number;
  className?: string;
  disabled?: boolean;
  id?: string;
}

function ColorInput({
  value,
  defaultValue = "#000000",
  onChange,
  showReset = true,
  debounceMs = 150,
  className,
  disabled,
  id
}: ColorInputProps) {
  // Internal state for debouncing
  const [internalValue, setInternalValue] = React.useState(value ?? defaultValue);
  const timeoutRef = React.useRef<ReturnType<typeof setTimeout> | null>(null);

  // Sync internal state when external value changes
  React.useEffect(() => {
    setInternalValue(value ?? defaultValue);
  }, [value, defaultValue]);

  // Handle color change with debouncing
  const handleColorChange = React.useCallback(
    (newValue: string) => {
      setInternalValue(newValue);

      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }

      timeoutRef.current = setTimeout(() => {
        onChange(newValue);
      }, debounceMs);
    },
    [onChange, debounceMs],
  );

  // Cleanup timeout on unmount
  React.useEffect(() => {
    return () => {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
    };
  }, []);

  // Handle reset
  const handleReset = React.useCallback(() => {
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
    }
    setInternalValue(defaultValue);
    onChange(undefined);
  }, [onChange, defaultValue]);

  return (
    <div className={cn("flex items-center gap-2 ", className)}>
      <ColorPicker
        value={internalValue}
        onValueChange={handleColorChange}
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
            <span className="text-xs font-normal text-muted-foreground">{internalValue}</span>
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
