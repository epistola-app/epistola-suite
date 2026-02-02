import {useId} from "react";
import {CircleQuestionMark} from "lucide-react";
import {useEditorStore} from "@/lib";
import {Label} from "../ui/label";
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from "../ui/select";
import {Tooltip, TooltipContent, TooltipTrigger} from "@/components/ui/tooltip";

export function DocumentPropertiesEditor() {
  const template = useEditorStore((s) => s.template);
  const themes = useEditorStore((s) => s.themes);
  const defaultTheme = useEditorStore((s) => s.defaultTheme);
  const updatePageSettings = useEditorStore((s) => s.updatePageSettings);
  const updateThemeId = useEditorStore((s) => s.updateThemeId);

  const themeId = useId();
  const formatId = useId();
  const orientationId = useId();
  const marginTopId = useId();
  const marginLeftId = useId();
  const marginRightId = useId();
  const marginBottomId = useId();

  const handleMarginChange = (side: "top" | "right" | "bottom" | "left", value: string) => {
    const numValue = parseFloat(value);
    if (!isNaN(numValue) && numValue >= 0) {
      updatePageSettings({
        margins: {
          ...template.pageSettings.margins,
          [side]: numValue,
        },
      });
    }
  };

  const handleFormatChange = (format: "A4" | "Letter" | "Custom") => {
    updatePageSettings({
      format,
    });
  };

  const handleOrientationChange = (orientation: "portrait" | "landscape") => {
    updatePageSettings({
      orientation,
    });
  };

  const handleThemeChange = (value: string) => {
    updateThemeId(value === "none" ? null : value);
  };

  return (
    <div className="p-3 space-y-4">
      <div>
        <Label>Template Name</Label>
        <div className="text-sm font-medium text-slate-700">{template.name}</div>
      </div>

      {themes.length > 0 && (
        <div className="flex flex-col gap-1">
          <Label htmlFor={themeId} className="flex items-end gap-1">
            Theme
            <Tooltip delayDuration={500}>
              <TooltipTrigger asChild>
                <CircleQuestionMark className="size-4 shrink-0 hover:text-primary" />
              </TooltipTrigger>
              <TooltipContent>
                Select a theme to apply base styling to this template.
                Template styles override theme defaults.
              </TooltipContent>
            </Tooltip>
          </Label>
          <Select value={template.themeId ?? "none"} onValueChange={handleThemeChange}>
            <SelectTrigger id={themeId} className="w-full h-8 text-xs">
              <SelectValue placeholder="No theme" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="none">
                {defaultTheme ? `Default (${defaultTheme.name})` : "No theme"}
              </SelectItem>
              {themes.map((theme) => (
                <SelectItem key={theme.id} value={theme.id}>
                  {theme.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}

      <div className="grid grid-cols-2 gap-3 items-end">
        <div className="flex flex-col gap-1">
          <Label htmlFor={formatId} className="flex items-end gap-1">
            Page Format
            <Tooltip delayDuration={500}>
              <TooltipTrigger asChild>
                <CircleQuestionMark className="size-4 shrink-0 hover:text-primary" />
              </TooltipTrigger>
              <TooltipContent>
                <table>
                  <tbody>
                    <tr>
                      <td className="min-w-12 pb-1">
                        <b>A4:</b>
                      </td>
                      <td className="pb-1">210mm x 297mm</td>
                    </tr>
                    <tr>
                      <td className="min-w-12">
                        <b>Letter:</b>
                      </td>
                      <td>216mm x 279mm</td>
                    </tr>
                  </tbody>
                </table>
              </TooltipContent>
            </Tooltip>
          </Label>
          <Select value={template.pageSettings.format} onValueChange={handleFormatChange}>
            <SelectTrigger id={formatId} className="w-full h-8 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="A4">A4</SelectItem>
              <SelectItem value="Letter">Letter</SelectItem>
              <SelectItem value="Custom">Custom</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <div className="flex flex-col gap-1">
          <Label htmlFor={orientationId}>Orientation</Label>
          <Select value={template.pageSettings.orientation} onValueChange={handleOrientationChange}>
            <SelectTrigger id={orientationId} className="w-full h-8 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="portrait">Portrait</SelectItem>
              <SelectItem value="landscape">Landscape</SelectItem>
            </SelectContent>
          </Select>
        </div>
      </div>

      <div>
        <Label>Margins (mm)</Label>
        <div className="grid grid-cols-3 gap-1 items-center mt-2">
          {/* Top */}
          <div className="col-start-2">
            <input
              id={marginTopId}
              type="number"
              value={template.pageSettings.margins.top}
              onChange={(e) => handleMarginChange("top", e.target.value)}
              min={0}
              className="w-full px-2 py-1 text-xs text-center border border-slate-200 rounded
                         focus:outline-none focus:border-blue-400
                         [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
              aria-label="Top margin"
            />
          </div>

          {/* Left */}
          <div className="col-start-1 row-start-2">
            <input
              id={marginLeftId}
              type="number"
              value={template.pageSettings.margins.left}
              onChange={(e) => handleMarginChange("left", e.target.value)}
              min={0}
              className="w-full px-2 py-1 text-xs text-center border border-slate-200 rounded
                         focus:outline-none focus:border-blue-400
                         [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
              aria-label="Left margin"
            />
          </div>

          {/* Center label */}
          <div className="col-start-2 row-start-2 flex justify-center">
            <span className="text-xs text-slate-400">mm</span>
          </div>

          {/* Right */}
          <div className="col-start-3 row-start-2">
            <input
              id={marginRightId}
              type="number"
              value={template.pageSettings.margins.right}
              onChange={(e) => handleMarginChange("right", e.target.value)}
              min={0}
              className="w-full px-2 py-1 text-xs text-center border border-slate-200 rounded
                         focus:outline-none focus:border-blue-400
                         [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
              aria-label="Right margin"
            />
          </div>

          {/* Bottom */}
          <div className="col-start-2 row-start-3">
            <input
              id={marginBottomId}
              type="number"
              value={template.pageSettings.margins.bottom}
              onChange={(e) => handleMarginChange("bottom", e.target.value)}
              min={0}
              className="w-full px-2 py-1 text-xs text-center border border-slate-200 rounded
                         focus:outline-none focus:border-blue-400
                         [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
              aria-label="Bottom margin"
            />
          </div>
        </div>
      </div>

      <div>
        <Label>Blocks</Label>
        <div className="text-sm text-slate-700">{template.blocks.length} top-level block(s)</div>
      </div>
    </div>
  );
}
