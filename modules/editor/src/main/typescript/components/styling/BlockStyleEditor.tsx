import type { CSSProperties } from 'react';
import { useEditorStore } from '../../store/editorStore';
import type { Block } from '../../types/template';
import { StyleSection } from './StyleSection';
import {
  ColorPicker,
  SelectInput,
  NumberInput,
  ToggleGroup,
  SpacingInput,
  SliderInput,
  BorderInput,
  BoxShadowInput,
} from './inputs';
import {
  FONT_FAMILIES,
  FONT_WEIGHTS,
  TEXT_ALIGN_OPTIONS,
  DISPLAY_OPTIONS,
  FLEX_DIRECTIONS,
  ALIGN_OPTIONS,
  JUSTIFY_OPTIONS,
} from '../../types/styles';

interface BlockStyleEditorProps {
  block: Block;
}

export function BlockStyleEditor({ block }: BlockStyleEditorProps) {
  const updateBlock = useEditorStore((s) => s.updateBlock);

  const styles = block.styles || {};

  const updateStyle = (key: keyof CSSProperties, value: unknown) => {
    const newStyles = { ...styles };
    if (value === undefined || value === '') {
      delete (newStyles as Record<string, unknown>)[key];
    } else {
      (newStyles as Record<string, unknown>)[key] = value;
    }
    updateBlock(block.id, {
      styles: Object.keys(newStyles).length > 0 ? newStyles : undefined,
    });
  };

  return (
    <div className="flex flex-col">
      <div className="px-3 py-2 border-b border-gray-200 bg-gray-50">
        <h3 className="text-sm font-semibold text-gray-700">
          Block Styles
        </h3>
        <p className="text-xs text-gray-500 mt-1">
          {block.type.charAt(0).toUpperCase() + block.type.slice(1)} block
        </p>
      </div>

      <StyleSection title="Spacing" defaultExpanded>
        <SpacingInput
          top={styles.paddingTop as string}
          right={styles.paddingRight as string}
          bottom={styles.paddingBottom as string}
          left={styles.paddingLeft as string}
          onChange={(side, value) => {
            const key = `padding${side.charAt(0).toUpperCase() + side.slice(1)}` as keyof CSSProperties;
            updateStyle(key, value);
          }}
          label="Padding"
        />
        <SpacingInput
          top={styles.marginTop as string}
          right={styles.marginRight as string}
          bottom={styles.marginBottom as string}
          left={styles.marginLeft as string}
          onChange={(side, value) => {
            const key = `margin${side.charAt(0).toUpperCase() + side.slice(1)}` as keyof CSSProperties;
            updateStyle(key, value);
          }}
          label="Margin"
        />
      </StyleSection>

      <StyleSection title="Typography">
        <SelectInput
          value={styles.fontFamily as string}
          onChange={(value) => updateStyle('fontFamily', value)}
          options={FONT_FAMILIES}
          label="Font Family"
          placeholder="Inherit"
        />
        <div className="grid grid-cols-2 gap-2">
          <NumberInput
            value={styles.fontSize as string}
            onChange={(value) => updateStyle('fontSize', value)}
            label="Font Size"
            units={['px', 'em', 'rem']}
            defaultUnit="px"
            min={1}
            placeholder="Inherit"
          />
          <SelectInput
            value={styles.fontWeight as string}
            onChange={(value) => updateStyle('fontWeight', value)}
            options={FONT_WEIGHTS}
            label="Font Weight"
            placeholder="Inherit"
          />
        </div>
        <ColorPicker
          value={styles.color as string}
          onChange={(value) => updateStyle('color', value)}
          label="Text Color"
        />
        <ToggleGroup
          value={styles.textAlign as string}
          onChange={(value) => updateStyle('textAlign', value)}
          options={TEXT_ALIGN_OPTIONS}
          label="Text Align"
        />
        <div className="grid grid-cols-2 gap-2">
          <NumberInput
            value={styles.lineHeight as string}
            onChange={(value) => updateStyle('lineHeight', value)}
            label="Line Height"
            units={['px', 'em', '%']}
            defaultUnit="em"
            min={0}
            step={0.1}
            placeholder="Inherit"
          />
          <NumberInput
            value={styles.letterSpacing as string}
            onChange={(value) => updateStyle('letterSpacing', value)}
            label="Letter Spacing"
            units={['px', 'em']}
            defaultUnit="px"
            placeholder="Inherit"
          />
        </div>
      </StyleSection>

      <StyleSection title="Background">
        <ColorPicker
          value={styles.backgroundColor as string}
          onChange={(value) => updateStyle('backgroundColor', value)}
          label="Background Color"
        />
      </StyleSection>

      <StyleSection title="Borders">
        <BorderInput
          width={styles.borderWidth as string}
          style={styles.borderStyle as string}
          color={styles.borderColor as string}
          radius={styles.borderRadius as string}
          onWidthChange={(value) => updateStyle('borderWidth', value)}
          onStyleChange={(value) => updateStyle('borderStyle', value)}
          onColorChange={(value) => updateStyle('borderColor', value)}
          onRadiusChange={(value) => updateStyle('borderRadius', value)}
        />
      </StyleSection>

      <StyleSection title="Effects">
        <BoxShadowInput
          value={styles.boxShadow as string}
          onChange={(value) => updateStyle('boxShadow', value)}
          label="Box Shadow"
        />
        <SliderInput
          value={styles.opacity as number}
          onChange={(value) => updateStyle('opacity', value)}
          min={0}
          max={1}
          step={0.05}
          label="Opacity"
        />
      </StyleSection>

      <StyleSection title="Layout">
        <div className="grid grid-cols-2 gap-2">
          <NumberInput
            value={styles.width as string}
            onChange={(value) => updateStyle('width', value)}
            label="Width"
            units={['px', '%', 'em']}
            defaultUnit="px"
            min={0}
            placeholder="auto"
          />
          <NumberInput
            value={styles.height as string}
            onChange={(value) => updateStyle('height', value)}
            label="Height"
            units={['px', '%', 'em']}
            defaultUnit="px"
            min={0}
            placeholder="auto"
          />
        </div>
        <SelectInput
          value={styles.display as string}
          onChange={(value) => updateStyle('display', value)}
          options={DISPLAY_OPTIONS}
          label="Display"
          placeholder="Default"
        />
        {styles.display === 'flex' && (
          <>
            <SelectInput
              value={styles.flexDirection as string}
              onChange={(value) => updateStyle('flexDirection', value)}
              options={FLEX_DIRECTIONS}
              label="Flex Direction"
              placeholder="Row"
            />
            <NumberInput
              value={styles.gap as string}
              onChange={(value) => updateStyle('gap', value)}
              label="Gap"
              units={['px', 'em', 'rem']}
              defaultUnit="px"
              min={0}
              placeholder="0"
            />
            <SelectInput
              value={styles.alignItems as string}
              onChange={(value) => updateStyle('alignItems', value)}
              options={ALIGN_OPTIONS}
              label="Align Items"
              placeholder="Stretch"
            />
            <SelectInput
              value={styles.justifyContent as string}
              onChange={(value) => updateStyle('justifyContent', value)}
              options={JUSTIFY_OPTIONS}
              label="Justify Content"
              placeholder="Start"
            />
          </>
        )}
      </StyleSection>
    </div>
  );
}
