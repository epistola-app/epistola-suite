import { useId } from "react";
import { AlignCenter, AlignJustify, AlignLeft, AlignRight, Palette, Type } from "lucide-react";
import { useEditorStore } from "../../store/editorStore";
import { FONT_FAMILIES, FONT_WEIGHTS, UNIT_PRESETS } from "../../types/styles";
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "../ui/accordion";
import { Button } from "../ui/button";
import { ButtonGroup } from "../ui/button-group";
import { ColorInput } from "../ui/color-input";
import { Label } from "../ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "../ui/select";
import { UnitInput } from "../ui/unit-input";

export function DocumentStyleEditor() {
  const documentStyles = useEditorStore((s) => s.template.documentStyles);
  const updateDocumentStyles = useEditorStore((s) => s.updateDocumentStyles);

  const fontFamilyId = useId();
  const fontSizeId = useId();
  const fontWeightId = useId();
  const textColorId = useId();
  const lineHeightId = useId();
  const letterSpacingId = useId();
  const bgColorId = useId();

  const handleTextAlignChange = (alignment: "left" | "center" | "right" | "justify") => {
    updateDocumentStyles({ textAlign: alignment });
  };

  return (
    <div className="flex flex-col">
      <div className="px-4 py-3 border-b border-slate-200 bg-gradient-to-r from-slate-50 to-white">
        <div className="flex items-center gap-2">
          <Type className="w-4 h-4 text-slate-500 shrink-0" />
          <h3 className="text-sm font-semibold text-slate-700 truncate">Document Styles</h3>
        </div>
        <p className="text-xs text-slate-500 mt-1 ml-6">
          These styles apply to all blocks unless overridden
        </p>
      </div>

      <Accordion type="single" collapsible className="w-full">
        <AccordionItem value="typography">
          <AccordionTrigger className="px-4 py-3 text-sm font-semibold text-slate-700 hover:bg-slate-50">
            <div className="flex items-center gap-2">
              <Type className="w-4 h-4 text-slate-500" />
              Typography
            </div>
          </AccordionTrigger>
          <AccordionContent className="p-4">
            <div className="space-y-4">
              <div className="flex flex-col gap-2">
                <Label htmlFor={fontFamilyId}>Font Family</Label>
                <Select
                  value={documentStyles?.fontFamily || ""}
                  onValueChange={(value) =>
                    updateDocumentStyles({ fontFamily: value || undefined })
                  }
                >
                  <SelectTrigger id={fontFamilyId} className="max-h-8">
                    <SelectValue placeholder="Default" />
                  </SelectTrigger>
                  <SelectContent>
                    {FONT_FAMILIES.map((family) => (
                      <SelectItem key={family.value} value={family.value}>
                        {family.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div className="flex flex-col gap-2">
                  <Label htmlFor={fontSizeId}>Font Size</Label>
                  <UnitInput
                    id={fontSizeId}
                    value={documentStyles?.fontSize || undefined}
                    onChange={(v) => updateDocumentStyles({ fontSize: v || undefined })}
                    units={UNIT_PRESETS.fontSize}
                    defaultUnit="px"
                    placeholder="16"
                    min={1}
                  />
                </div>

                <div className="flex flex-col gap-2">
                  <Label htmlFor={fontWeightId}>Font Weight</Label>
                  <Select
                    value={documentStyles?.fontWeight || undefined}
                    onValueChange={(value) =>
                      updateDocumentStyles({ fontWeight: value || undefined })
                    }
                  >
                    <SelectTrigger id={fontWeightId} className="max-h-8 w-full">
                      <SelectValue placeholder="Normal" />
                    </SelectTrigger>
                    <SelectContent>
                      {FONT_WEIGHTS.map((weight) => (
                        <SelectItem key={weight.value} value={weight.value}>
                          {weight.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              </div>

              <div className="flex flex-col gap-2">
                <Label htmlFor={textColorId}>Text Color</Label>
                <ColorInput
                  id={textColorId}
                  value={documentStyles?.color}
                  defaultValue="#000000"
                  onChange={(v) => updateDocumentStyles({ color: v })}
                />
              </div>

              <div className="flex flex-col gap-2">
                <Label>Text Alignment</Label>
                <ButtonGroup className="w-full">
                  <Button
                    variant={documentStyles?.textAlign === "left" ? "default" : "outline"}
                    size="sm"
                    onClick={() => handleTextAlignChange("left")}
                    className="flex-1"
                    aria-label="Align left"
                  >
                    <AlignLeft className="w-4 h-4" />
                  </Button>
                  <Button
                    variant={documentStyles?.textAlign === "center" ? "default" : "outline"}
                    size="sm"
                    onClick={() => handleTextAlignChange("center")}
                    className="flex-1"
                    aria-label="Align center"
                  >
                    <AlignCenter className="w-4 h-4" />
                  </Button>
                  <Button
                    variant={documentStyles?.textAlign === "right" ? "default" : "outline"}
                    size="sm"
                    onClick={() => handleTextAlignChange("right")}
                    className="flex-1"
                    aria-label="Align right"
                  >
                    <AlignRight className="w-4 h-4" />
                  </Button>
                  <Button
                    variant={documentStyles?.textAlign === "justify" ? "default" : "outline"}
                    size="sm"
                    onClick={() => handleTextAlignChange("justify")}
                    className="flex-1"
                    aria-label="Justify"
                  >
                    <AlignJustify className="w-4 h-4" />
                  </Button>
                </ButtonGroup>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div className="flex flex-col gap-2">
                  <Label htmlFor={lineHeightId}>Line Height</Label>
                  <UnitInput
                    id={lineHeightId}
                    value={documentStyles?.lineHeight}
                    onChange={(v) => updateDocumentStyles({ lineHeight: v })}
                    units={UNIT_PRESETS.lineHeight}
                    defaultUnit="em"
                    placeholder="1.5"
                    min={0}
                    step={0.1}
                  />
                </div>

                <div className="flex flex-col gap-2">
                  <Label htmlFor={letterSpacingId}>Letter Spacing</Label>
                  <UnitInput
                    id={letterSpacingId}
                    value={documentStyles?.letterSpacing}
                    onChange={(v) => updateDocumentStyles({ letterSpacing: v })}
                    units={UNIT_PRESETS.spacing}
                    defaultUnit="px"
                    placeholder="0"
                  />
                </div>
              </div>
            </div>
          </AccordionContent>
        </AccordionItem>

        <AccordionItem value="background">
          <AccordionTrigger className="px-4 py-3 text-sm font-semibold text-slate-700 hover:bg-slate-50">
            <div className="flex items-center gap-2">
              <Palette className="w-4 h-4 text-slate-500" />
              Background
            </div>
          </AccordionTrigger>
          <AccordionContent className="p-4">
            <div className="space-y-4">
              <div className="flex flex-col gap-2">
                <Label htmlFor={bgColorId}>Background Color</Label>
                <ColorInput
                  id={bgColorId}
                  value={documentStyles?.backgroundColor}
                  defaultValue="#ffffff"
                  onChange={(v) => updateDocumentStyles({ backgroundColor: v })}
                />
              </div>
            </div>
          </AccordionContent>
        </AccordionItem>
      </Accordion>
    </div>
  );
}
