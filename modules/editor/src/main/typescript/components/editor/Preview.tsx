import { useState, useEffect } from 'react';
import { useEditorStore } from '../../store/editorStore';
import { useEvaluator } from '../../context/EvaluatorContext';
import type { Block, TextBlock, ConditionalBlock, LoopBlock, ContainerBlock, ColumnsBlock, TableBlock, DocumentStyles } from '../../types/template';
import type { EvaluationResult, EvaluationContext } from '../../services/expression';
import { mergeStyles } from '../../types/styles';

// Type for the async evaluate function passed to render functions
type EvaluateFn = (expr: string, context: EvaluationContext) => Promise<EvaluationResult>;

export function Preview() {
  const template = useEditorStore((s) => s.template);
  const testData = useEditorStore((s) => s.testData);
  const previewOverrides = useEditorStore((s) => s.previewOverrides);
  const { evaluate, isReady } = useEvaluator();
  const [renderedHtml, setRenderedHtml] = useState('<p style="color: #666;">Loading...</p>');

  useEffect(() => {
    if (!isReady) {
      setRenderedHtml('<p style="color: #666;">Loading evaluator...</p>');
      return;
    }

    let cancelled = false;

    // Debounce rendering to avoid excessive updates
    const timer = setTimeout(async () => {
      try {
        const html = await renderTemplate(
          template.blocks,
          testData,
          previewOverrides,
          {},
          evaluate,
          template.documentStyles
        );
        if (!cancelled) {
          setRenderedHtml(html);
        }
      } catch (error) {
        if (!cancelled) {
          setRenderedHtml(`<p style="color: red;">Render error: ${error}</p>`);
        }
      }
    }, 100);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [template.blocks, template.documentStyles, testData, previewOverrides, evaluate, isReady]);

  return (
    <div className="h-full p-4">
      <div className="bg-white shadow-lg rounded-lg overflow-hidden h-full flex flex-col">
        <div className="bg-gray-100 px-4 py-2 border-b border-gray-200 flex items-center justify-between">
          <span className="text-sm font-medium text-gray-600">Preview</span>
          <span className="text-xs text-gray-400">A4 Portrait</span>
        </div>
        <div className="flex-1 overflow-auto p-8 bg-white">
          {/* A4 aspect ratio container */}
          <div
            className="mx-auto shadow-md"
            style={{
              width: "210mm",
              minHeight: "297mm",
              padding: `${template.pageSettings.margins.top}mm ${template.pageSettings.margins.right}mm ${template.pageSettings.margins.bottom}mm ${template.pageSettings.margins.left}mm`,
              transform: "scale(0.5)",
              transformOrigin: "top center",
              backgroundColor:
                template.documentStyles?.backgroundColor || "#ffffff",
              fontFamily: template.documentStyles?.fontFamily,
              fontSize: template.documentStyles?.fontSize,
              fontWeight: template.documentStyles?.fontWeight,
              color: template.documentStyles?.color,
              lineHeight: template.documentStyles?.lineHeight,
              letterSpacing: template.documentStyles?.letterSpacing,
              textAlign: template.documentStyles?.textAlign,
            }}
            dangerouslySetInnerHTML={{ __html: renderedHtml }}
          />
        </div>
      </div>
    </div>
  );
}

// Async template renderer
async function renderTemplate(
  blocks: Block[],
  data: Record<string, unknown>,
  overrides: { conditionals: Record<string, 'data' | 'show' | 'hide'>; loops: Record<string, number | 'data'> },
  context: Record<string, unknown> = {},
  evaluate: EvaluateFn,
  documentStyles?: DocumentStyles
): Promise<string> {
  const rendered = await Promise.all(
    blocks.map((block) => renderBlock(block, data, overrides, context, evaluate, documentStyles))
  );
  return rendered.join('');
}

async function renderBlock(
  block: Block,
  data: Record<string, unknown>,
  overrides: { conditionals: Record<string, 'data' | 'show' | 'hide'>; loops: Record<string, number | 'data'> },
  context: Record<string, unknown>,
  evaluate: EvaluateFn,
  documentStyles?: DocumentStyles
): Promise<string> {
  const mergedContext = { ...data, ...context };

  switch (block.type) {
    case 'text':
      return renderTextBlock(block, mergedContext, evaluate, documentStyles);
    case 'container':
      return renderContainerBlock(block, data, overrides, context, evaluate, documentStyles);
    case 'conditional':
      return renderConditionalBlock(block, data, overrides, context, evaluate, documentStyles);
    case 'loop':
      return renderLoopBlock(block, data, overrides, context, evaluate, documentStyles);
    case 'columns':
      return renderColumnsBlock(block, data, overrides, context, evaluate, documentStyles);
    case 'table':
      return renderTableBlock(block, data, overrides, context, evaluate, documentStyles);
    default:
      return '';
  }
}

async function renderTextBlock(
  block: TextBlock,
  context: Record<string, unknown>,
  evaluate: EvaluateFn,
  documentStyles?: DocumentStyles
): Promise<string> {
  const html = tiptapToHtml(block.content);
  const evaluatedHtml = await evaluateExpressions(html, context, evaluate);

  // Apply block styles if present, merging with inherited document styles
  const mergedStyles = mergeStyles(documentStyles as React.CSSProperties, block.styles);
  if (Object.keys(mergedStyles).length > 0) {
    return `<div style="${styleToString(mergedStyles)}">${evaluatedHtml}</div>`;
  }
  return evaluatedHtml;
}

async function renderContainerBlock(
  block: ContainerBlock,
  data: Record<string, unknown>,
  overrides: { conditionals: Record<string, 'data' | 'show' | 'hide'>; loops: Record<string, number | 'data'> },
  context: Record<string, unknown>,
  evaluate: EvaluateFn,
  documentStyles?: DocumentStyles
): Promise<string> {
  // Merge document styles with block styles
  const mergedStyles = mergeStyles(documentStyles as React.CSSProperties, block.styles);
  const style = Object.keys(mergedStyles).length > 0 ? styleToString(mergedStyles) : '';
  const inner = await renderTemplate(block.children, data, overrides, context, evaluate, documentStyles);
  return `<div style="${style}">${inner}</div>`;
}

async function renderColumnsBlock(
  block: ColumnsBlock,
  data: Record<string, unknown>,
  overrides: { conditionals: Record<string, 'data' | 'show' | 'hide'>; loops: Record<string, number | 'data'> },
  context: Record<string, unknown>,
  evaluate: EvaluateFn,
  documentStyles?: DocumentStyles
): Promise<string> {
  const gap = block.gap ?? 16;

  // Merge document styles with block styles
  const mergedStyles = mergeStyles(documentStyles as React.CSSProperties, block.styles);
  const containerStyle = {
    ...mergedStyles,
    display: 'flex',
    gap: `${gap}px`,
  };

  // Render each column
  const columnHtmls = await Promise.all(
    block.columns.map(async (column) => {
      const inner = await renderTemplate(column.children, data, overrides, context, evaluate, documentStyles);
      return `<div style="flex: ${column.size};">${inner}</div>`;
    })
  );

  return `<div style="${styleToString(containerStyle)}">${columnHtmls.join('')}</div>`;
}

async function renderTableBlock(
  block: TableBlock,
  data: Record<string, unknown>,
  overrides: { conditionals: Record<string, 'data' | 'show' | 'hide'>; loops: Record<string, number | 'data'> },
  context: Record<string, unknown>,
  evaluate: EvaluateFn,
  documentStyles?: DocumentStyles
): Promise<string> {
  const borderStyle = block.borderStyle || 'all';

  // Determine table border style
  let tableBorder = '';
  let cellBorder = '';

  switch (borderStyle) {
    case 'all':
      tableBorder = 'border: 1px solid #d1d5db; border-collapse: collapse;';
      cellBorder = 'border: 1px solid #d1d5db;';
      break;
    case 'horizontal':
      tableBorder = 'border-top: 1px solid #d1d5db; border-bottom: 1px solid #d1d5db; border-collapse: collapse;';
      cellBorder = 'border-bottom: 1px solid #d1d5db;';
      break;
    case 'vertical':
      tableBorder = 'border-left: 1px solid #d1d5db; border-right: 1px solid #d1d5db; border-collapse: collapse;';
      cellBorder = 'border-right: 1px solid #d1d5db;';
      break;
    case 'none':
    default:
      tableBorder = 'border-collapse: collapse;';
      cellBorder = '';
      break;
  }

  // Merge document styles with block styles
  const mergedStyles = mergeStyles(documentStyles as React.CSSProperties, block.styles);
  const tableStyle = `${styleToString(mergedStyles)}${tableBorder}`.trim();

  // Render rows
  const rowsHtml = await Promise.all(
    block.rows.map(async (row, rowIndex) => {
      // Track which cells are occupied by previous cells' colspan/rowspan
      const occupiedCells = new Set<number>();

      // Check cells from previous rows for rowspan
      for (let prevRowIdx = 0; prevRowIdx < rowIndex; prevRowIdx++) {
        const prevRow = block.rows[prevRowIdx];
        let colIdx = 0;
        prevRow.cells.forEach((prevCell) => {
          const rowspan = prevCell.rowspan || 1;
          const colspan = prevCell.colspan || 1;

          // If this cell spans into the current row
          if (prevRowIdx + rowspan > rowIndex) {
            for (let c = 0; c < colspan; c++) {
              occupiedCells.add(colIdx + c);
            }
          }
          colIdx += colspan;
        });
      }

      const cellsHtml = await Promise.all(
        row.cells.map(async (cell, cellIndex) => {
          // Calculate actual column index accounting for previous cells' colspan
          let actualColIndex = 0;
          for (let i = 0; i < cellIndex; i++) {
            actualColIndex += row.cells[i].colspan || 1;
          }

          // Skip this cell if it's occupied by a previous cell's merge
          if (occupiedCells.has(actualColIndex)) {
            return '';
          }

          const cellTag = row.isHeader ? 'th' : 'td';
          const colspan = cell.colspan || 1;
          const rowspan = cell.rowspan || 1;

          // Render blocks inside the cell
          const cellContentHtml = await renderTemplate(cell.children, data, overrides, context, evaluate, documentStyles);

          const cellStyleStr = cell.styles ? styleToString(cell.styles) : '';
          const combinedCellStyle = `padding: 8px; ${cellBorder} ${cellStyleStr}`.trim();

          const colspanAttr = colspan > 1 ? ` colspan="${colspan}"` : '';
          const rowspanAttr = rowspan > 1 ? ` rowspan="${rowspan}"` : '';

          return `<${cellTag} style="${combinedCellStyle}"${colspanAttr}${rowspanAttr}>${cellContentHtml}</${cellTag}>`;
        })
      );
      return `<tr>${cellsHtml.join('')}</tr>`;
    })
  );

  return `<table style="width: 100%; ${tableStyle}"><tbody>${rowsHtml.join('')}</tbody></table>`;
}

async function renderConditionalBlock(
  block: ConditionalBlock,
  data: Record<string, unknown>,
  overrides: { conditionals: Record<string, 'data' | 'show' | 'hide'>; loops: Record<string, number | 'data'> },
  context: Record<string, unknown>,
  evaluate: EvaluateFn,
  documentStyles?: DocumentStyles
): Promise<string> {
  // Evaluate condition from data
  const mergedContext = { ...data, ...context };
  const result = await evaluate(block.condition.raw, mergedContext);
  let shouldShow = result.success ? Boolean(result.value) : false;

  // Apply inverse if set
  if (block.inverse) {
    shouldShow = !shouldShow;
  }

  if (shouldShow) {
    return renderTemplate(block.children, data, overrides, context, evaluate, documentStyles);
  }
  return '';
}

async function renderLoopBlock(
  block: LoopBlock,
  data: Record<string, unknown>,
  overrides: { conditionals: Record<string, 'data' | 'show' | 'hide'>; loops: Record<string, number | 'data'> },
  context: Record<string, unknown>,
  evaluate: EvaluateFn,
  documentStyles?: DocumentStyles
): Promise<string> {
  const override = overrides.loops[block.id];

  // Get array from data
  const mergedContext = { ...data, ...context };
  const result = await evaluate(block.expression.raw, mergedContext);
  const array: unknown[] = result.success && Array.isArray(result.value) ? result.value : [];

  // Apply override
  const count = override === 'data' || override === undefined ? array.length : override;

  // Expand or truncate array to match count
  const items: unknown[] = [];
  for (let i = 0; i < count; i++) {
    items.push(array[i % array.length] || {});
  }

  const rendered = await Promise.all(
    items.map((item, index) => {
      const loopContext = {
        ...context,
        [block.itemAlias]: item,
        ...(block.indexAlias ? { [block.indexAlias]: index } : {}),
      };
      return renderTemplate(block.children, data, overrides, loopContext, evaluate, documentStyles);
    })
  );
  return rendered.join('');
}

// Helper functions
interface TiptapNode {
  type: string;
  content?: TiptapNode[];
  text?: string;
  marks?: { type: string; attrs?: Record<string, unknown> }[];
  attrs?: Record<string, unknown>;
}

function renderInlineContent(content?: TiptapNode[]): string {
  if (!content) return '';

  return content
    .map((child) => {
      if (child.type === 'text') {
        let text = child.text || '';
        // Handle marks
        if (child.marks) {
          for (const mark of child.marks) {
            switch (mark.type) {
              case 'bold':
                text = `<strong>${text}</strong>`;
                break;
              case 'italic':
                text = `<em>${text}</em>`;
                break;
              case 'underline':
                text = `<u>${text}</u>`;
                break;
              case 'strike':
                text = `<s>${text}</s>`;
                break;
            }
          }
        }
        return text;
      }
      // Handle expression atom nodes
      if (child.type === 'expression') {
        const expr = child.attrs?.expression || '';
        return `{{${expr}}}`;
      }
      return '';
    })
    .join('');
}

function tiptapToHtml(content: TextBlock['content']): string {
  if (!content || !content.content) return '';

  return content.content
    .map((node) => {
      if (!node.type) return '';
      switch (node.type) {
        case 'paragraph': {
          const inner = renderInlineContent(node.content as TiptapNode[]);
          return `<p style="margin: 0 0 1em 0;">${inner}</p>`;
        }
        case 'heading': {
          const level = node.attrs?.level || 1;
          const inner = renderInlineContent(node.content as TiptapNode[]);
          const sizes: Record<number, string> = {
            1: 'font-size: 2em; font-weight: bold;',
            2: 'font-size: 1.5em; font-weight: bold;',
            3: 'font-size: 1.17em; font-weight: bold;',
          };
          return `<h${level} style="margin: 0 0 0.5em 0; ${sizes[level as number] || ''}">${inner}</h${level}>`;
        }
        case 'bulletList': {
          const items = node.content
            ?.map((item) => {
              if (item.type === 'listItem') {
                const itemContent = item.content
                  ?.map((child) => {
                    if (child.type === 'paragraph') {
                      return renderInlineContent(child.content as TiptapNode[]);
                    }
                    return '';
                  })
                  .join('');
                return `<li>${itemContent}</li>`;
              }
              return '';
            })
            .join('') || '';
          return `<ul style="margin: 0 0 1em 0; padding-left: 1.5em;">${items}</ul>`;
        }
        case 'orderedList': {
          const items = node.content
            ?.map((item) => {
              if (item.type === 'listItem') {
                const itemContent = item.content
                  ?.map((child) => {
                    if (child.type === 'paragraph') {
                      return renderInlineContent(child.content as TiptapNode[]);
                    }
                    return '';
                  })
                  .join('');
                return `<li>${itemContent}</li>`;
              }
              return '';
            })
            .join('') || '';
          return `<ol style="margin: 0 0 1em 0; padding-left: 1.5em;">${items}</ol>`;
        }
        default:
          return '';
      }
    })
    .join('');
}

async function evaluateExpressions(html: string, context: Record<string, unknown>, evaluate: EvaluateFn): Promise<string> {
  // Find all {{expression}} patterns
  const regex = /\{\{([^}]+)\}\}/g;
  const matches: { match: string; expr: string; index: number }[] = [];
  let match;

  while ((match = regex.exec(html)) !== null) {
    matches.push({ match: match[0], expr: match[1].trim(), index: match.index });
  }

  if (matches.length === 0) return html;

  // Evaluate all expressions in parallel
  const results = await Promise.all(
    matches.map(async ({ expr }) => {
      const result = await evaluate(expr, context);
      if (result.success) {
        return String(result.value ?? '');
      }
      return `[Error: ${expr}]`;
    })
  );

  // Replace from end to start to preserve indices
  let result = html;
  for (let i = matches.length - 1; i >= 0; i--) {
    const { match, index } = matches[i];
    result = result.slice(0, index) + results[i] + result.slice(index + match.length);
  }

  return result;
}

function styleToString(styles: React.CSSProperties): string {
  return Object.entries(styles)
    .map(([key, value]) => {
      const cssKey = key.replace(/([A-Z])/g, '-$1').toLowerCase();
      return `${cssKey}: ${value}`;
    })
    .join('; ');
}
