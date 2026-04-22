import { html, nothing } from 'lit';
import type { RecentUsageCompatibilityIssue, RecentUsageCompatibilitySummary } from '../types.js';

/* oxlint-disable eslint/no-use-before-define */

const LATEST_INCOMPATIBLE_WINDOW = 20;
const PERIOD_OPTIONS = [
  { value: '24h', label: '24h', hours: 24 },
  { value: '3d', label: '3d', hours: 72 },
  { value: '7d', label: '7d', hours: 168 },
  { value: '30d', label: '30d', hours: 720 },
] as const;

type PeriodOptionValue = (typeof PERIOD_OPTIONS)[number]['value'];
export type PublishRiskDialogMode = 'latest-risk' | 'older-risk' | 'unavailable';

export interface PublishRiskDialogState {
  mode: PublishRiskDialogMode;
  message: string;
  recentUsage: RecentUsageCompatibilitySummary | null;
  selectedPeriod: PeriodOptionValue;
}

export interface PublishRiskDialogCallbacks {
  onCancel: () => void;
  onConfirm: () => void;
  onPeriodChange: (period: PeriodOptionValue) => void;
}

interface FilteredRecentUsage {
  checkedCount: number;
  compatibleCount: number;
  incompatibleCount: number;
  issues: RecentUsageCompatibilityIssue[];
}

function isPeriodOptionValue(value: string): value is PeriodOptionValue {
  return PERIOD_OPTIONS.some((option) => option.value === value);
}

export function buildPublishRiskDialogState(
  recentUsage: RecentUsageCompatibilitySummary,
): PublishRiskDialogState {
  if (!recentUsage.available) {
    return {
      mode: 'unavailable',
      message:
        recentUsage.unavailableReason ??
        'Recent usage compatibility could not be checked before publishing.',
      recentUsage,
      selectedPeriod: defaultPeriod(),
    };
  }

  let newestIncompatibleRank = Number.POSITIVE_INFINITY;
  for (const issue of recentUsage.issues) {
    if (issue.sampleRank < newestIncompatibleRank) {
      newestIncompatibleRank = issue.sampleRank;
    }
  }

  if (
    Number.isFinite(newestIncompatibleRank) &&
    newestIncompatibleRank <= LATEST_INCOMPATIBLE_WINDOW
  ) {
    return {
      mode: 'latest-risk',
      message:
        'Newest generation requests are incompatible with this draft. Publishing may break future requests unless request payloads have already been updated.',
      recentUsage,
      selectedPeriod: defaultPeriod(),
    };
  }

  return {
    mode: 'older-risk',
    message:
      'Newest generation requests look compatible. Some older recent generations are incompatible, so review the historical mismatch before publishing.',
    recentUsage,
    selectedPeriod: defaultPeriod(),
  };
}

export function renderPublishRiskDialog(
  dialog: PublishRiskDialogState,
  callbacks: PublishRiskDialogCallbacks,
  maxRenderedIssues = Number.POSITIVE_INFINITY,
): unknown {
  const recentUsage = dialog.recentUsage;
  const filtered = filterRecentUsage(recentUsage, dialog.selectedPeriod);

  return html`
    <div class="dc-import-dialog">
      <h3 class="dc-dialog-title">${dialogTitle(dialog.mode)}</h3>
      <p class="dc-dialog-hint">${dialog.message}</p>

      ${recentUsage
        ? html`
            ${recentUsage.available
              ? html`<div class="dc-detail-row">
                  <label class="dc-detail-label" for="publish-risk-period">Period</label>
                  <select
                    id="publish-risk-period"
                    class="ep-select dc-detail-select"
                    .value=${dialog.selectedPeriod}
                    @change=${(event: Event) => {
                      const target = event.currentTarget;
                      if (!(target instanceof HTMLSelectElement)) {
                        return;
                      }
                      const value = target.value;
                      if (!isPeriodOptionValue(value)) {
                        return;
                      }
                      callbacks.onPeriodChange(value);
                    }}
                  >
                    ${availablePeriods(recentUsage.window.maxDays).map(
                      (option) => html`<option value=${option.value}>${option.label}</option>`,
                    )}
                  </select>
                </div>`
              : nothing}
            <div class="dc-fix-warnings">
              <div><strong>Checked:</strong> ${filtered.checkedCount}</div>
              <div><strong>Compatible:</strong> ${filtered.compatibleCount}</div>
              <div><strong>Incompatible:</strong> ${filtered.incompatibleCount}</div>
            </div>
          `
        : nothing}
      ${filtered.issues.length > 0
        ? html`
            <div class="dc-fix-groups">
              ${filtered.issues
                .slice(0, maxRenderedIssues)
                .map((issue) => renderPublishRiskIssue(issue))}
            </div>
          `
        : nothing}

      <div class="dc-dialog-actions">
        <button class="btn btn-sm btn-ghost" @click=${callbacks.onCancel}>Cancel</button>
        <button class="btn btn-sm btn-primary" @click=${callbacks.onConfirm}>Publish Anyway</button>
      </div>
    </div>
  `;
}

function defaultPeriod(): PeriodOptionValue {
  return '24h';
}

function availablePeriods(maxDays: number): readonly (typeof PERIOD_OPTIONS)[number][] {
  return PERIOD_OPTIONS.filter((option) => option.hours <= maxDays * 24);
}

function filterRecentUsage(
  recentUsage: RecentUsageCompatibilitySummary | null,
  period: PeriodOptionValue,
): FilteredRecentUsage {
  const samples = recentUsage ? recentUsage.samples : [];
  const issues = recentUsage ? recentUsage.issues : [];
  const cutoff = cutoffForPeriod(period);
  const filteredSamples = samples.filter(
    (sample) => new Date(sample.createdAt).getTime() >= cutoff,
  );
  const visibleRequestIds = new Set(filteredSamples.map((sample) => sample.requestId));
  const filteredIssues = issues.filter((issue) => visibleRequestIds.has(issue.requestId));

  return {
    checkedCount: filteredSamples.length,
    compatibleCount: filteredSamples.filter((sample) => sample.compatible).length,
    incompatibleCount: filteredSamples.filter((sample) => !sample.compatible).length,
    issues: filteredIssues,
  };
}

function cutoffForPeriod(period: PeriodOptionValue): number {
  const option = PERIOD_OPTIONS.find((entry) => entry.value === period) ?? PERIOD_OPTIONS[0];
  return Date.now() - option.hours * 60 * 60 * 1000;
}

function dialogTitle(mode: PublishRiskDialogMode): string {
  switch (mode) {
    case 'latest-risk':
      return 'Newest Requests Are Incompatible';
    case 'older-risk':
      return 'Older Requests Are Incompatible';
    case 'unavailable':
      return 'Recent Usage Check Unavailable';
    default:
      return 'Recent Usage Check';
  }
}

function renderPublishRiskIssue(issue: RecentUsageCompatibilityIssue): unknown {
  return html`
    <div class="dc-fix-group">
      <div class="dc-fix-group-header">
        <span class="dc-fix-group-name">#${issue.sampleRank} ${issue.requestId}</span>
        <span class="dc-fix-group-count">${issue.status}</span>
      </div>
      <div class="dc-dialog-hint">
        ${new Date(issue.createdAt).toLocaleString()}${issue.correlationKey
          ? html` | correlation: ${issue.correlationKey}`
          : nothing}
      </div>
      <div class="dc-fix-group-fields">
        ${issue.errors.map(
          (error) =>
            html`<div class="dc-fix-field"><code>${error.path}</code> ${error.message}</div>`,
        )}
      </div>
    </div>
  `;
}
