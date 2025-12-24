import { useEditorStore } from "../../store/editorStore";
import { StyleSection } from "./StyleSection";
import { ColorPicker, SelectInput, NumberInput, ToggleGroup } from "./inputs";
import { FONT_FAMILIES, FONT_WEIGHTS, TEXT_ALIGN_OPTIONS } from "../../types/styles";

export function DocumentStyleEditor() {
  const documentStyles = useEditorStore((s) => s.template.documentStyles);
  const updateDocumentStyles = useEditorStore((s) => s.updateDocumentStyles);

  return (
    <div className="flex flex-col">
      <div className="px-3 py-2 border-b border-gray-200 bg-gray-50">
        <h3 className="text-sm font-semibold text-gray-700">Document Styles</h3>
        <p className="text-xs text-gray-500 mt-1">
          These styles apply to all blocks unless overridden
        </p>
      </div>

      <StyleSection title="Typography" defaultExpanded>
        <SelectInput
          value={documentStyles?.fontFamily}
          onChange={(value) => updateDocumentStyles({ fontFamily: value })}
          options={FONT_FAMILIES}
          label="Font Family"
          placeholder="Default"
        />
        <div className="grid grid-cols-2 gap-2">
          <NumberInput
            value={documentStyles?.fontSize}
            onChange={(value) => updateDocumentStyles({ fontSize: value })}
            label="Font Size"
            units={["px", "em", "rem"]}
            defaultUnit="px"
            min={1}
            placeholder="16"
          />
          <SelectInput
            value={documentStyles?.fontWeight}
            onChange={(value) => updateDocumentStyles({ fontWeight: value })}
            options={FONT_WEIGHTS}
            label="Font Weight"
            placeholder="Normal"
          />
        </div>
        <ColorPicker
          value={documentStyles?.color}
          onChange={(value) => updateDocumentStyles({ color: value })}
          label="Text Color"
        />
        <ToggleGroup
          value={documentStyles?.textAlign}
          onChange={(value) =>
            updateDocumentStyles({
              textAlign: value as "left" | "center" | "right" | "justify" | undefined,
            })
          }
          options={TEXT_ALIGN_OPTIONS}
          label="Text Align"
        />
        <div className="grid grid-cols-2 gap-2">
          <NumberInput
            value={documentStyles?.lineHeight}
            onChange={(value) => updateDocumentStyles({ lineHeight: value })}
            label="Line Height"
            units={["px", "em", "%"]}
            defaultUnit="em"
            min={0}
            step={0.1}
            placeholder="1.5"
          />
          <NumberInput
            value={documentStyles?.letterSpacing}
            onChange={(value) => updateDocumentStyles({ letterSpacing: value })}
            label="Letter Spacing"
            units={["px", "em"]}
            defaultUnit="px"
            placeholder="0"
          />
        </div>
      </StyleSection>

      <StyleSection title="Background">
        <ColorPicker
          value={documentStyles?.backgroundColor}
          onChange={(value) => updateDocumentStyles({ backgroundColor: value })}
          label="Background Color"
        />
      </StyleSection>
    </div>
  );
}
