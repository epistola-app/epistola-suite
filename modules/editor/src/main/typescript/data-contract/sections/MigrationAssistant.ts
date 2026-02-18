/**
 * MigrationAssistant — Dialog for reviewing and applying schema migrations.
 *
 * Uses the native HTML <dialog> element (.showModal() / .close()).
 * Groups migration suggestions by example, shows checkboxes for auto-migratable
 * items, and provides Apply / Save Anyway / Cancel actions.
 */

import { html, nothing } from 'lit'
import type { MigrationSuggestion, MigrationIssueType } from '../utils/schemaMigration.js'

export interface MigrationDialogCallbacks {
  onApply: (selectedMigrations: MigrationSuggestion[]) => void
  onForceSave: () => void
  onCancel: () => void
  onToggleMigration: (migration: MigrationSuggestion) => void
  onSelectAll: () => void
  onSelectNone: () => void
}

/** Issue type labels for display */
const ISSUE_LABELS: Record<MigrationIssueType, string> = {
  TYPE_MISMATCH: 'Type mismatch',
  MISSING_REQUIRED: 'Missing required',
  UNKNOWN_FIELD: 'Unknown field',
}

/**
 * Render the migration assistant dialog content.
 *
 * The caller is responsible for managing the <dialog> element itself.
 * This function renders the content that goes inside the dialog.
 */
export function renderMigrationDialog(
  migrations: MigrationSuggestion[],
  selectedMigrations: Set<string>,
  callbacks: MigrationDialogCallbacks,
): unknown {
  if (migrations.length === 0) return nothing

  // Group by example
  const grouped = groupByExample(migrations)
  const autoMigratableCount = migrations.filter((m) => m.autoMigratable).length
  const selectedCount = selectedMigrations.size

  return html`
    <div class="dc-migration-dialog">
      <div class="dc-migration-header">
        <h3 class="dc-migration-title">Schema Changes Detected</h3>
        <p class="dc-migration-subtitle">
          ${migrations.length} issue${migrations.length !== 1 ? 's' : ''} found across
          ${grouped.length} example${grouped.length !== 1 ? 's' : ''}.
          ${autoMigratableCount > 0
            ? html`${autoMigratableCount} can be auto-fixed.`
            : html`None can be auto-fixed.`
          }
        </p>
      </div>

      <!-- Select all/none controls -->
      ${autoMigratableCount > 0
        ? html`
            <div class="dc-migration-select-controls">
              <button
                class="btn btn-sm btn-ghost"
                @click=${() => callbacks.onSelectAll()}
              >Select all</button>
              <button
                class="btn btn-sm btn-ghost"
                @click=${() => callbacks.onSelectNone()}
              >Select none</button>
            </div>
          `
        : nothing
      }

      <!-- Grouped migration items -->
      <div class="dc-migration-groups">
        ${grouped.map(({ exampleName, migrations: exampleMigrations }) => html`
          <div class="dc-migration-group">
            <div class="dc-migration-group-header">${exampleName}</div>
            <div class="dc-migration-group-items">
              ${exampleMigrations.map((m) => renderMigrationItem(m, selectedMigrations, callbacks))}
            </div>
          </div>
        `)}
      </div>

      <!-- Footer actions -->
      <div class="dc-migration-footer">
        <button
          class="btn btn-sm btn-ghost"
          @click=${() => callbacks.onCancel()}
        >Cancel</button>

        <button
          class="btn btn-sm dc-migration-force-save-btn"
          @click=${() => callbacks.onForceSave()}
          title="Save schema without fixing examples"
        >Save Anyway</button>

        ${autoMigratableCount > 0
          ? html`
              <button
                class="btn btn-sm btn-primary"
                ?disabled=${selectedCount === 0}
                @click=${() => {
                  const selected = migrations.filter(
                    (m) => selectedMigrations.has(migrationKey(m)),
                  )
                  callbacks.onApply(selected)
                }}
              >Apply ${selectedCount} Fix${selectedCount !== 1 ? 'es' : ''}</button>
            `
          : nothing
        }
      </div>
    </div>
  `
}

/**
 * Render a single migration item with checkbox/icon and details.
 */
function renderMigrationItem(
  migration: MigrationSuggestion,
  selectedMigrations: Set<string>,
  callbacks: MigrationDialogCallbacks,
): unknown {
  const key = migrationKey(migration)
  const isSelected = selectedMigrations.has(key)

  return html`
    <div class="dc-migration-item ${migration.autoMigratable ? '' : 'dc-migration-item-manual'}">
      <div class="dc-migration-item-check">
        ${migration.autoMigratable
          ? html`
              <input
                type="checkbox"
                class="ep-checkbox"
                .checked=${isSelected}
                aria-label="Apply fix for ${migration.path}"
                @change=${() => callbacks.onToggleMigration(migration)}
              />
            `
          : html`<span class="dc-migration-item-icon" title="Cannot be auto-fixed">\u2717</span>`
        }
      </div>
      <div class="dc-migration-item-details">
        <div class="dc-migration-item-path">
          <code>${migration.path}</code>
          <span class="badge badge-warning dc-migration-issue-badge">
            ${ISSUE_LABELS[migration.issue]}
          </span>
        </div>
        ${migration.issue === 'TYPE_MISMATCH'
          ? html`
              <div class="dc-migration-item-conversion">
                <span class="dc-migration-current">${formatValue(migration.currentValue)}</span>
                <span class="dc-migration-arrow">\u2192</span>
                <span class="dc-migration-expected">${migration.expectedType}</span>
                ${migration.suggestedValue !== null
                  ? html`
                      <span class="dc-migration-arrow">\u2192</span>
                      <span class="dc-migration-suggested">${formatValue(migration.suggestedValue)}</span>
                    `
                  : nothing
                }
              </div>
            `
          : nothing
        }
        ${migration.issue === 'MISSING_REQUIRED'
          ? html`
              <div class="dc-migration-item-info">
                Expected type: <code>${migration.expectedType}</code>
              </div>
            `
          : nothing
        }
      </div>
    </div>
  `
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

interface MigrationGroup {
  exampleName: string
  migrations: MigrationSuggestion[]
}

function groupByExample(migrations: MigrationSuggestion[]): MigrationGroup[] {
  const groups = new Map<string, MigrationGroup>()
  for (const m of migrations) {
    const existing = groups.get(m.exampleId)
    if (existing) {
      existing.migrations.push(m)
    } else {
      groups.set(m.exampleId, { exampleName: m.exampleName, migrations: [m] })
    }
  }
  return Array.from(groups.values())
}

/** Generate a unique key for a migration suggestion. */
export function migrationKey(m: MigrationSuggestion): string {
  return `${m.exampleId}:${m.path}:${m.issue}`
}

/** Format a JSON value for display. */
function formatValue(value: unknown): string {
  if (value === null) return 'null'
  if (value === undefined) return 'undefined'
  if (typeof value === 'string') return `"${value}"`
  return String(value)
}
