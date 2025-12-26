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
import { useDebounce } from "@uidotdev/usehooks";

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
  id,
}: ColorInputProps) {
  // Internal state for immediate UI updates
  const [internalValue, setInternalValue] = React.useState(value ?? defaultValue);
  const debouncedValue = useDebounce(internalValue, debounceMs);
  const isInitializedRef = React.useRef(false);

  // Sync internal state when external value changes
  React.useEffect(() => {
    setInternalValue(value ?? defaultValue);
  }, [value, defaultValue]);

  // Call onChange when debounced value changes (skip initial)
  React.useEffect(() => {
    if (!isInitializedRef.current) {
      isInitializedRef.current = true;
      return;
    }
    onChange(debouncedValue);
  }, [debouncedValue, onChange]);

  // Handle color change - update internal state immediately
  const handleColorChange = React.useCallback((newValue: string) => {
    setInternalValue(newValue);
  }, []);

  // Handle reset
  const handleReset = React.useCallback(() => {
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
