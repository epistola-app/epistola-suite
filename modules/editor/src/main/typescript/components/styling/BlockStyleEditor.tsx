import {
  AlignCenter,
  AlignJustify,
  AlignLeft,
  AlignRight,
  Layout,
  Move,
  Palette,
  Square,
  Type,
  Zap,
} from "lucide-react";
import { useCallback, useId, useMemo, type CSSProperties } from "react";
import { useEditorStore } from "../../store/editorStore";
import {
  ALIGN_OPTIONS,
  BORDER_STYLES,
  DISPLAY_OPTIONS,
  FLEX_DIRECTIONS,
  FONT_FAMILIES,
  FONT_WEIGHTS,
  JUSTIFY_OPTIONS,
  UNIT_PRESETS,
} from "../../types/styles";
import type { Block } from "../../types/template";
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "../ui/accordion";
import { Button } from "../ui/button";
import { ButtonGroup } from "../ui/button-group";
import { ColorInput } from "../ui/color-input";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "../ui/select";
import { Slider } from "../ui/slider";
import { UnitInput } from "../ui/unit-input";

interface BlockStyleEditorProps {
  block: Block;
}

export function BlockStyleEditor({ block }: BlockStyleEditorProps) {
  const updateBlock = useEditorStore((s) => s.updateBlock);
  const id = useId();

  const styles = useMemo(() => block.styles || {}, [block.styles]);

  const updateStyle = useCallback(
    (key: keyof CSSProperties, value: unknown) => {
      const newStyles = { ...styles };
      if (value === undefined || value === "") {
        delete (newStyles as Record<string, unknown>)[key];
      } else {
        (newStyles as Record<string, unknown>)[key] = value;
      }
      updateBlock(block.id, {
        styles: Object.keys(newStyles).length > 0 ? newStyles : undefined,
      });
    },
    [styles, block.id, updateBlock],
  );

  const handleTextAlignChange = (alignment: "left" | "center" | "right" | "justify") => {
    // Toggle off if clicking the same alignment
    if (styles.textAlign === alignment) {
      updateStyle("textAlign", undefined);
    } else {
      updateStyle("textAlign", alignment);
    }
  };

  return (
    <div className="flex flex-col">
      <div className="px-4 py-3 border-b border-slate-200 bg-linear-to-r from-slate-50 to-white">
        <div className="flex items-center gap-2">
          <Square className="w-4 h-4 text-slate-500 shrink-0" />
          <h3 className="text-sm font-semibold text-slate-700">Block Styles</h3>
        </div>
        <p className="text-xs text-slate-500 mt-1 ml-6">
          {block.type.charAt(0).toUpperCase() + block.type.slice(1)} block
        </p>
      </div>

      <Accordion type="single" collapsible className="w-full">
        <AccordionItem value="spacing">
          <AccordionTrigger className="px-4 py-3 text-sm font-semibold text-slate-700 hover:bg-slate-50">
            <div className="flex items-center gap-2">
              <Move className="w-4 h-4 text-slate-500 shrink-0" />
              Spacing
            </div>
          </AccordionTrigger>
          <AccordionContent className="px-4 pb-4">
            <div className="space-y-4">
              <div className="text-xs text-slate-500 font-medium">Padding</div>
              <div className="grid grid-cols-2 gap-2">
                <div className="flex flex-col gap-1">
                  <Label htmlFor={`${id}-padding-top`} className="text-xs text-slate-500">
                    Top
                  </Label>
                  <UnitInput
                    id={`${id}-padding-top`}
                    value={styles.paddingTop as string}
                    onChange={(v) => updateStyle("paddingTop", v)}
                    units={UNIT_PRESETS.spacing}
                    defaultUnit="px"
                    placeholder="0"
                    min={0}
                  />
                </div>
                <div className="flex flex-col gap-1">
                  <Label htmlFor={`${id}-padding-right`} className="text-xs text-slate-500">
                    Right
                  </Label>
                  <UnitInput
                    id={`${id}-padding-right`}
                    value={styles.paddingRight as string}
                    onChange={(v) => updateStyle("paddingRight", v)}
                    units={UNIT_PRESETS.spacing}
                    defaultUnit="px"
                    placeholder="0"
                    min={0}
                  />
                </div>
                <div className="flex flex-col gap-1">
                  <Label htmlFor={`${id}-padding-bottom`} className="text-xs text-slate-500">
                    Bottom
                  </Label>
                  <UnitInput
                    id={`${id}-padding-bottom`}
                    value={styles.paddingBottom as string}
                    onChange={(v) => updateStyle("paddingBottom", v)}
                    units={UNIT_PRESETS.spacing}
                    defaultUnit="px"
                    placeholder="0"
                    min={0}
                  />
                </div>
                <div className="flex flex-col gap-1">
                  <Label htmlFor={`${id}-padding-left`} className="text-xs text-slate-500">
                    Left
                  </Label>
                  <UnitInput
                    id={`${id}-padding-left`}
                    value={styles.paddingLeft as string}
                    onChange={(v) => updateStyle("paddingLeft", v)}
                    units={UNIT_PRESETS.spacing}
                    defaultUnit="px"
                    placeholder="0"
                    min={0}
                  />
                </div>
              </div>

              <div className="text-xs text-slate-500 font-medium">Margin</div>
              <div className="grid grid-cols-2 gap-2">
                <div className="flex flex-col gap-1">
                  <Label htmlFor={`${id}-margin-top`} className="text-xs text-slate-500">
                    Top
                  </Label>
                  <UnitInput
                    id={`${id}-margin-top`}
                    value={styles.marginTop as string}
                    onChange={(v) => updateStyle("marginTop", v)}
                    units={UNIT_PRESETS.spacing}
                    defaultUnit="px"
                    placeholder="0"
                  />
                </div>
                <div className="flex flex-col gap-1">
                  <Label htmlFor={`${id}-margin-right`} className="text-xs text-slate-500">
                    Right
                  </Label>
                  <UnitInput
                    id={`${id}-margin-right`}
                    value={styles.marginRight as string}
                    onChange={(v) => updateStyle("marginRight", v)}
                    units={UNIT_PRESETS.spacing}
                    defaultUnit="px"
                    placeholder="0"
                  />
                </div>
                <div className="flex flex-col gap-1">
                  <Label htmlFor={`${id}-margin-bottom`} className="text-xs text-slate-500">
                    Bottom
                  </Label>
                  <UnitInput
                    id={`${id}-margin-bottom`}
                    value={styles.marginBottom as string}
                    onChange={(v) => updateStyle("marginBottom", v)}
                    units={UNIT_PRESETS.spacing}
                    defaultUnit="px"
                    placeholder="0"
                  />
                </div>
                <div className="flex flex-col gap-1">
                  <Label htmlFor={`${id}-margin-left`} className="text-xs text-slate-500">
                    Left
                  </Label>
                  <UnitInput
                    id={`${id}-margin-left`}
                    value={styles.marginLeft as string}
                    onChange={(v) => updateStyle("marginLeft", v)}
                    units={UNIT_PRESETS.spacing}
                    defaultUnit="px"
                    placeholder="0"
                  />
                </div>
              </div>
            </div>
          </AccordionContent>
        </AccordionItem>

        <AccordionItem value="typography">
          <AccordionTrigger className="px-4 py-3 text-sm font-semibold text-slate-700 hover:bg-slate-50">
            <div className="flex items-center gap-2">
              <Type className="w-4 h-4 text-slate-500 shrink-0" />
              Typography
            </div>
          </AccordionTrigger>
          <AccordionContent className="px-4 pb-4">
            <div className="space-y-4">
              <div className="flex flex-col gap-2">
                <Label htmlFor={`${id}-font-family`} className="text-xs text-slate-500 font-medium">
                  Font Family
                </Label>
                <Select
                  value={styles.fontFamily || ""}
                  onValueChange={(value) => updateStyle("fontFamily", value || undefined)}
                >
                  <SelectTrigger id={`${id}-font-family`} size="sm">
                    <SelectValue placeholder="Inherit" />
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
                  <Label htmlFor={`${id}-font-size`} className="text-xs text-slate-500 font-medium">
                    Font Size
                  </Label>
                  <UnitInput
                    id={`${id}-font-size`}
                    value={styles.fontSize as string}
                    onChange={(v) => updateStyle("fontSize", v)}
                    units={UNIT_PRESETS.fontSize}
                    defaultUnit="px"
                    placeholder="16"
                    min={1}
                  />
                </div>

                <div className="flex flex-col gap-2">
                  <Label
                    htmlFor={`${id}-font-weight`}
                    className="text-xs text-slate-500 font-medium"
                  >
                    Font Weight
                  </Label>
                  <Select
                    value={styles.fontWeight?.toString() || undefined}
                    onValueChange={(value) => updateStyle("fontWeight", value || undefined)}
                  >
                    <SelectTrigger id={`${id}-font-weight`} size="sm" className="w-full">
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
                <Label htmlFor={`${id}-text-color`} className="text-xs text-slate-500 font-medium">
                  Text Color
                </Label>
                <ColorInput
                  id={`${id}-text-color`}
                  value={styles.color as string}
                  defaultValue="#000000"
                  onChange={(v) => updateStyle("color", v)}
                />
              </div>

              <div className="flex flex-col gap-2">
                <Label className="text-xs text-slate-500 font-medium">Text Alignment</Label>
                <ButtonGroup className="w-full">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => handleTextAlignChange("left")}
                    className={`flex-1 ${styles.textAlign === "left" ? "bg-slate-100" : ""}`}
                    title="Align left"
                  >
                    <AlignLeft className="w-4 h-4 shrink-0" />
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => handleTextAlignChange("center")}
                    className={`flex-1 ${styles.textAlign === "center" ? "bg-slate-100" : ""}`}
                    title="Align center"
                  >
                    <AlignCenter className="w-4 h-4 shrink-0" />
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => handleTextAlignChange("right")}
                    className={`flex-1 ${styles.textAlign === "right" ? "bg-slate-100" : ""}`}
                    title="Align right"
                  >
                    <AlignRight className="w-4 h-4 shrink-0" />
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => handleTextAlignChange("justify")}
                    className={`flex-1 ${styles.textAlign === "justify" ? "bg-slate-100" : ""}`}
                    title="Justify"
                  >
                    <AlignJustify className="w-4 h-4 shrink-0" />
                  </Button>
                </ButtonGroup>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div className="flex flex-col gap-2">
                  <Label
                    htmlFor={`${id}-line-height`}
                    className="text-xs text-slate-500 font-medium"
                  >
                    Line Height
                  </Label>
                  <UnitInput
                    id={`${id}-line-height`}
                    value={styles.lineHeight as string}
                    onChange={(v) => updateStyle("lineHeight", v)}
                    units={UNIT_PRESETS.lineHeight}
                    defaultUnit="em"
                    placeholder="1.5"
                    min={0}
                    step={0.1}
                  />
                </div>

                <div className="flex flex-col gap-2">
                  <Label
                    htmlFor={`${id}-letter-spacing`}
                    className="text-xs text-slate-500 font-medium"
                  >
                    Letter Spacing
                  </Label>
                  <UnitInput
                    id={`${id}-letter-spacing`}
                    value={styles.letterSpacing as string}
                    onChange={(v) => updateStyle("letterSpacing", v)}
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
              <Palette className="w-4 h-4 text-slate-500 shrink-0" />
              Background
            </div>
          </AccordionTrigger>
          <AccordionContent className="px-4 pb-4">
            <div className="space-y-4">
              <div className="flex flex-col gap-2">
                <Label htmlFor={`${id}-bg-color`} className="text-xs text-slate-500 font-medium">
                  Background Color
                </Label>
                <ColorInput
                  id={`${id}-bg-color`}
                  value={styles.backgroundColor as string}
                  defaultValue="#ffffff"
                  onChange={(v) => updateStyle("backgroundColor", v)}
                />
              </div>
            </div>
          </AccordionContent>
        </AccordionItem>

        <AccordionItem value="borders">
          <AccordionTrigger className="px-4 py-3 text-sm font-semibold text-slate-700 hover:bg-slate-50">
            <div className="flex items-center gap-2">
              <Square className="w-4 h-4 text-slate-500" />
              Borders
            </div>
          </AccordionTrigger>
          <AccordionContent className="px-4 pb-4">
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-3">
                <div className="flex flex-col gap-2">
                  <Label
                    htmlFor={`${id}-border-width`}
                    className="text-xs text-slate-500 font-medium"
                  >
                    Border Width
                  </Label>
                  <UnitInput
                    id={`${id}-border-width`}
                    value={styles.borderWidth as string}
                    onChange={(v) => updateStyle("borderWidth", v)}
                    units={UNIT_PRESETS.borderWidth}
                    defaultUnit="px"
                    placeholder="0"
                    min={0}
                  />
                </div>
                <div className="flex flex-col gap-2">
                  <Label
                    htmlFor={`${id}-border-style`}
                    className="text-xs text-slate-500 font-medium"
                  >
                    Border Style
                  </Label>
                  <Select
                    value={styles.borderStyle || ""}
                    onValueChange={(value) => updateStyle("borderStyle", value || undefined)}
                  >
                    <SelectTrigger id={`${id}-border-style`} size="sm">
                      <SelectValue placeholder="none" />
                    </SelectTrigger>
                    <SelectContent>
                      {BORDER_STYLES.map((style) => (
                        <SelectItem key={style.value} value={style.value}>
                          {style.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              </div>

              <div className="flex flex-col gap-2">
                <Label
                  htmlFor={`${id}-border-color`}
                  className="text-xs text-slate-500 font-medium"
                >
                  Border Color
                </Label>
                <ColorInput
                  id={`${id}-border-color`}
                  value={styles.borderColor as string}
                  defaultValue="#000000"
                  onChange={(v) => updateStyle("borderColor", v)}
                />
              </div>

              <div className="flex flex-col gap-2">
                <Label
                  htmlFor={`${id}-border-radius`}
                  className="text-xs text-slate-500 font-medium"
                >
                  Border Radius
                </Label>
                <UnitInput
                  id={`${id}-border-radius`}
                  value={styles.borderRadius as string}
                  onChange={(v) => updateStyle("borderRadius", v)}
                  units={UNIT_PRESETS.borderRadius}
                  defaultUnit="px"
                  placeholder="0"
                  min={0}
                />
              </div>
            </div>
          </AccordionContent>
        </AccordionItem>

        <AccordionItem value="effects">
          <AccordionTrigger className="px-4 py-3 text-sm font-semibold text-slate-700 hover:bg-slate-50">
            <div className="flex items-center gap-2">
              <Zap className="w-4 h-4 text-slate-500" />
              Effects
            </div>
          </AccordionTrigger>
          <AccordionContent className="px-4 pb-4">
            <div className="space-y-4">
              <div className="flex flex-col gap-2">
                <Label htmlFor={`${id}-box-shadow`} className="text-xs text-slate-500 font-medium">
                  Box Shadow
                </Label>
                <Input
                  id={`${id}-box-shadow`}
                  type="text"
                  value={styles.boxShadow || ""}
                  onChange={(e) => updateStyle("boxShadow", e.target.value || undefined)}
                  placeholder="none"
                />
              </div>

              <div className="flex flex-col gap-2">
                <Label htmlFor={`${id}-opacity`} className="text-xs text-slate-500 font-medium">
                  Opacity
                </Label>
                <div className="px-2">
                  <Slider
                    id={`${id}-opacity`}
                    value={[typeof styles.opacity === "number" ? styles.opacity : 1]}
                    onValueChange={(values) => updateStyle("opacity", values[0])}
                    min={0}
                    max={1}
                    step={0.05}
                    className="w-full"
                  />
                  <div className="flex justify-between text-xs text-slate-500 mt-1">
                    <span>0%</span>
                    <span>
                      {Math.round((typeof styles.opacity === "number" ? styles.opacity : 1) * 100)}%
                    </span>
                    <span>100%</span>
                  </div>
                </div>
              </div>
            </div>
          </AccordionContent>
        </AccordionItem>

        <AccordionItem value="layout">
          <AccordionTrigger className="px-4 py-3 text-sm font-semibold text-slate-700 hover:bg-slate-50">
            <div className="flex items-center gap-2">
              <Layout className="w-4 h-4 text-slate-500" />
              Layout
            </div>
          </AccordionTrigger>
          <AccordionContent className="px-4 pb-4">
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-3">
                <div className="flex flex-col gap-2">
                  <Label htmlFor={`${id}-width`} className="text-xs text-slate-500 font-medium">
                    Width
                  </Label>
                  <UnitInput
                    id={`${id}-width`}
                    value={styles.width as string}
                    onChange={(v) => updateStyle("width", v)}
                    units={UNIT_PRESETS.size}
                    defaultUnit="px"
                    placeholder="auto"
                    min={0}
                  />
                </div>

                <div className="flex flex-col gap-2">
                  <Label htmlFor={`${id}-height`} className="text-xs text-slate-500 font-medium">
                    Height
                  </Label>
                  <UnitInput
                    id={`${id}-height`}
                    value={styles.height as string}
                    onChange={(v) => updateStyle("height", v)}
                    units={UNIT_PRESETS.size}
                    defaultUnit="px"
                    placeholder="auto"
                    min={0}
                  />
                </div>
              </div>

              <div className="flex flex-col gap-2">
                <Label htmlFor={`${id}-display`} className="text-xs text-slate-500 font-medium">
                  Display
                </Label>
                <Select
                  value={styles.display || ""}
                  onValueChange={(value) => updateStyle("display", value || undefined)}
                >
                  <SelectTrigger id={`${id}-display`} size="sm">
                    <SelectValue placeholder="Default" />
                  </SelectTrigger>
                  <SelectContent>
                    {DISPLAY_OPTIONS.map((option) => (
                      <SelectItem key={option.value} value={option.value}>
                        {option.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              {styles.display === "flex" && (
                <div className="space-y-4 border-t border-slate-200 pt-4">
                  <div className="flex flex-col gap-2">
                    <Label
                      htmlFor={`${id}-flex-direction`}
                      className="text-xs text-slate-500 font-medium"
                    >
                      Flex Direction
                    </Label>
                    <Select
                      value={styles.flexDirection || ""}
                      onValueChange={(value) => updateStyle("flexDirection", value || undefined)}
                    >
                      <SelectTrigger id={`${id}-flex-direction`} size="sm">
                        <SelectValue placeholder="Row" />
                      </SelectTrigger>
                      <SelectContent>
                        {FLEX_DIRECTIONS.map((option) => (
                          <SelectItem key={option.value} value={option.value}>
                            {option.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>

                  <div className="flex flex-col gap-2">
                    <Label htmlFor={`${id}-gap`} className="text-xs text-slate-500 font-medium">
                      Gap
                    </Label>
                    <UnitInput
                      id={`${id}-gap`}
                      value={styles.gap as string}
                      onChange={(v) => updateStyle("gap", v)}
                      units={UNIT_PRESETS.spacing}
                      defaultUnit="px"
                      placeholder="0"
                      min={0}
                    />
                  </div>

                  <div className="grid grid-cols-2 gap-3">
                    <div className="flex flex-col gap-2">
                      <Label
                        htmlFor={`${id}-align-items`}
                        className="text-xs text-slate-500 font-medium"
                      >
                        Align Items
                      </Label>
                      <Select
                        value={styles.alignItems || ""}
                        onValueChange={(value) => updateStyle("alignItems", value || undefined)}
                      >
                        <SelectTrigger id={`${id}-align-items`} size="sm">
                          <SelectValue placeholder="Stretch" />
                        </SelectTrigger>
                        <SelectContent>
                          {ALIGN_OPTIONS.map((option) => (
                            <SelectItem key={option.value} value={option.value}>
                              {option.label}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>

                    <div className="flex flex-col gap-2">
                      <Label
                        htmlFor={`${id}-justify-content`}
                        className="text-xs text-slate-500 font-medium"
                      >
                        Justify Content
                      </Label>
                      <Select
                        value={styles.justifyContent || ""}
                        onValueChange={(value) => updateStyle("justifyContent", value || undefined)}
                      >
                        <SelectTrigger id={`${id}-justify-content`} size="sm">
                          <SelectValue placeholder="Start" />
                        </SelectTrigger>
                        <SelectContent>
                          {JUSTIFY_OPTIONS.map((option) => (
                            <SelectItem key={option.value} value={option.value}>
                              {option.label}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                  </div>
                </div>
              )}
            </div>
          </AccordionContent>
        </AccordionItem>
      </Accordion>
    </div>
  );
}
