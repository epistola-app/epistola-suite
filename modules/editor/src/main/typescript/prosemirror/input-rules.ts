/**
 * ProseMirror input rules for Epistola.
 *
 * - `{{` triggers expression node insertion
 * - `# `, `## `, `### ` triggers heading conversion
 */

import {
  inputRules,
  textblockTypeInputRule,
  InputRule,
} from 'prosemirror-inputrules'
import type { Schema } from 'prosemirror-model'

/**
 * Input rule: typing `{{` inserts an expression node with `isNew: true`.
 */
function expressionInputRule(schema: Schema): InputRule {
  return new InputRule(/\{\{$/, (state, _match, start, end) => {
    const expressionType = schema.nodes.expression
    if (!expressionType) return null

    const node = expressionType.create({ expression: '', isNew: true })
    return state.tr.replaceWith(start, end, node)
  })
}

/**
 * Heading input rules: `# ` → h1, `## ` → h2, `### ` → h3
 */
function headingInputRules(schema: Schema): InputRule[] {
  const headingType = schema.nodes.heading
  if (!headingType) return []

  return [
    textblockTypeInputRule(/^#\s$/, headingType, { level: 1 }),
    textblockTypeInputRule(/^##\s$/, headingType, { level: 2 }),
    textblockTypeInputRule(/^###\s$/, headingType, { level: 3 }),
  ]
}

/**
 * Create the inputRules plugin with all Epistola rules.
 */
export function epistolaInputRules(schema: Schema) {
  return inputRules({
    rules: [
      expressionInputRule(schema),
      ...headingInputRules(schema),
    ],
  })
}

export { expressionInputRule, headingInputRules }
