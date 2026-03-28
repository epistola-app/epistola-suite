import {
  SHORTCUT_CONTEXT_IDS,
  assertValidShortcutRegistry,
  isShortcutContextId,
  type CommandDefinition,
  type CommandExecutionContext,
  type CommandId,
  type CommandResult,
  type KeybindingDefinition,
  type ShortcutContextId,
  type ShortcutRegistryDefinition,
} from "./foundation.js";

const MODIFIER_TOKEN_ORDER = ["mod", "shift", "alt"] as const;

const DEFAULT_FALLBACK_CONTEXTS: readonly ShortcutContextId[] = ["global"];
const DEFAULT_CHORD_TIMEOUT_MS = 1600;
const DEFAULT_CHORD_CANCEL_KEYS = ["escape"] as const;

const KEY_ALIAS_MAP: Record<string, string> = {
  esc: "escape",
  spacebar: "space",
  " ": "space",
  up: "arrowup",
  down: "arrowdown",
  left: "arrowleft",
  right: "arrowright",
};

type ShortcutMatchBy = "key" | "code";

interface NormalizedBindingStroke {
  matchBy: ShortcutMatchBy;
  stroke: string;
  sourceStroke: string;
}

interface NormalizedBindingSequence {
  sourceSequence: string;
  strokes: readonly NormalizedBindingStroke[];
}

interface PreparedBinding<TContext> {
  order: number;
  binding: KeybindingDefinition<TContext>;
  command: CommandDefinition<TContext>;
  sequences: readonly NormalizedBindingSequence[];
}

interface ChordCandidate<TContext> {
  entry: PreparedBinding<TContext>;
  sequence: NormalizedBindingSequence;
}

interface ActiveChordState<TContext> {
  context: ShortcutContextId;
  candidates: readonly ChordCandidate<TContext>[];
  nextStepIndex: number;
  startedAtMs: number;
  expiresAtMs: number;
  matchedStrokes: readonly string[];
}

export interface ShortcutKeyboardEvent {
  key: string;
  code: string;
  ctrlKey: boolean;
  metaKey: boolean;
  shiftKey: boolean;
  altKey: boolean;
  preventDefault?: () => void;
  stopPropagation?: () => void;
}

export interface NormalizedShortcutEvent {
  key: string;
  code: string;
  mod: boolean;
  shift: boolean;
  alt: boolean;
  ctrl: boolean;
  meta: boolean;
  keyStroke: string;
  codeStroke: string;
}

export interface ShortcutChordOptions {
  timeoutMs: number;
  cancelKeys?: readonly string[];
}

export interface ShortcutResolverOptions {
  fallbackContexts?: readonly ShortcutContextId[];
  chord?: Partial<ShortcutChordOptions>;
}

export interface ShortcutResolveInput<TContext = unknown> {
  event: ShortcutKeyboardEvent;
  activeContexts: readonly ShortcutContextId[];
  runtimeContext: TContext;
  timestampMs?: number;
}

export interface ShortcutResolutionMatch<TContext> {
  commandId: CommandId;
  context: ShortcutContextId;
  command: CommandDefinition<TContext>;
  binding: KeybindingDefinition<TContext>;
  normalizedEvent: NormalizedShortcutEvent;
  matchedStroke: string;
  matchBy: ShortcutMatchBy;
}

export interface ShortcutEventPolicyResult {
  preventDefault: boolean;
  stopPropagation: boolean;
}

export interface ShortcutChordStateSnapshot {
  context: ShortcutContextId;
  commandIds: readonly CommandId[];
  startedAtMs: number;
  expiresAtMs: number;
  matchedStrokes: readonly string[];
  remainingSteps: number;
}

export type ShortcutChordCancelReason =
  | "timeout"
  | "cancel-key"
  | "mismatch"
  | "context-changed"
  | "manual";

export type ShortcutCommandExecutionStatus = "pending" | "ok" | "rejected" | "error" | "cancelled";

export interface ShortcutCommandExecutionResult {
  status: ShortcutCommandExecutionStatus;
  message?: string;
  errorCode?: string;
}

export interface ShortcutCommandExecutionHandle {
  initial: ShortcutCommandExecutionResult;
  completion: Promise<ShortcutCommandExecutionResult>;
  cancel: (reason?: string) => void;
  isPending: () => boolean;
}

export interface ShortcutResolutionCommand<TContext> {
  kind: "command";
  match: ShortcutResolutionMatch<TContext>;
  fromChord: boolean;
  eventPolicy: ShortcutEventPolicyResult;
  chord?: ShortcutChordStateSnapshot;
}

export interface ShortcutResolutionChordAwaiting {
  kind: "chord-awaiting";
  state: ShortcutChordStateSnapshot;
  eventPolicy: ShortcutEventPolicyResult;
}

export interface ShortcutResolutionChordCancelled {
  kind: "chord-cancelled";
  reason: ShortcutChordCancelReason;
}

export interface ShortcutResolutionNone {
  kind: "none";
}

export type ShortcutResolution<TContext> =
  | ShortcutResolutionCommand<TContext>
  | ShortcutResolutionChordAwaiting
  | ShortcutResolutionChordCancelled
  | ShortcutResolutionNone;

function normalizeToken(rawValue: string): string {
  if (rawValue === " ") {
    return "space";
  }

  const trimmed = rawValue.trim();
  if (trimmed.length === 0) {
    return "";
  }

  const lower = trimmed.toLowerCase();
  return KEY_ALIAS_MAP[lower] ?? lower;
}

function serializeStroke(input: {
  mod: boolean;
  shift: boolean;
  alt: boolean;
  key: string;
}): string {
  if (!input.key) {
    return "";
  }

  const parts: string[] = [];
  for (const token of MODIFIER_TOKEN_ORDER) {
    if (input[token]) {
      parts.push(token);
    }
  }
  parts.push(input.key);
  return parts.join("+");
}

function normalizeEventKey(value: string): string {
  return normalizeToken(value);
}

function normalizeEventCode(value: string): string {
  return normalizeToken(value);
}

function parseStrokeToken(
  rawToken: string,
  defaultMatchBy: ShortcutMatchBy,
): NormalizedBindingStroke | null {
  const compact = rawToken.trim().toLowerCase();
  if (!compact) {
    return null;
  }

  const parts = compact.split("+").filter((part) => part.length > 0);
  if (parts.length === 0) {
    return null;
  }

  let keyToken = "";
  let mod = false;
  let shift = false;
  let alt = false;
  let matchBy: ShortcutMatchBy = defaultMatchBy;

  for (const part of parts) {
    if (part === "mod") {
      mod = true;
      continue;
    }
    if (part === "shift") {
      shift = true;
      continue;
    }
    if (part === "alt") {
      alt = true;
      continue;
    }

    if (keyToken.length > 0) {
      return null;
    }

    if (part.startsWith("code:")) {
      matchBy = "code";
      keyToken = part.slice("code:".length);
      continue;
    }

    keyToken = part;
  }

  if (!keyToken) {
    return null;
  }

  const normalizedKey =
    matchBy === "code" ? normalizeEventCode(keyToken) : normalizeEventKey(keyToken);
  const stroke = serializeStroke({ mod, shift, alt, key: normalizedKey });
  if (!stroke) {
    return null;
  }

  return {
    matchBy,
    stroke,
    sourceStroke: compact,
  };
}

function parseBindingSequence<TContext>(
  binding: KeybindingDefinition<TContext>,
  rawSequence: string,
): NormalizedBindingSequence | null {
  const normalizedSequence = rawSequence
    .trim()
    .toLowerCase()
    .replace(/\s*\+\s*/g, "+")
    .replace(/\s+/g, " ");
  if (!normalizedSequence) {
    return null;
  }

  const strokeTokens = normalizedSequence.split(" ").filter((token) => token.length > 0);
  if (strokeTokens.length === 0) {
    return null;
  }

  const defaultMatchBy = binding.matchBy ?? "key";
  const strokes: NormalizedBindingStroke[] = [];
  for (const token of strokeTokens) {
    const stroke = parseStrokeToken(token, defaultMatchBy);
    if (!stroke) {
      return null;
    }
    strokes.push(stroke);
  }

  return {
    sourceSequence: normalizedSequence,
    strokes,
  };
}

function buildFallbackContexts(
  fallbackContexts?: readonly ShortcutContextId[],
): readonly ShortcutContextId[] {
  const contexts = fallbackContexts ?? DEFAULT_FALLBACK_CONTEXTS;
  const ordered: ShortcutContextId[] = [];

  for (const context of contexts) {
    if (!isShortcutContextId(context)) {
      continue;
    }
    if (!ordered.includes(context)) {
      ordered.push(context);
    }
  }

  if (ordered.length === 0) {
    return [...DEFAULT_FALLBACK_CONTEXTS];
  }

  return ordered;
}

function buildContextPriority(
  activeContexts: readonly ShortcutContextId[],
  fallbackContexts: readonly ShortcutContextId[],
): readonly ShortcutContextId[] {
  const ordered: ShortcutContextId[] = [];

  for (const context of activeContexts) {
    if (!isShortcutContextId(context)) {
      continue;
    }
    if (!ordered.includes(context)) {
      ordered.push(context);
    }
  }

  for (const context of fallbackContexts) {
    if (!ordered.includes(context)) {
      ordered.push(context);
    }
  }

  return ordered;
}

function matchesStroke(stroke: NormalizedBindingStroke, event: NormalizedShortcutEvent): boolean {
  const eventStroke = stroke.matchBy === "code" ? event.codeStroke : event.keyStroke;
  return eventStroke === stroke.stroke;
}

function eventPolicyFromBinding<TContext>(
  binding: KeybindingDefinition<TContext>,
): ShortcutEventPolicyResult {
  return {
    preventDefault: binding.preventDefault ?? false,
    stopPropagation: binding.stopPropagation ?? false,
  };
}

function mergeEventPolicy<TContext>(
  bindings: readonly KeybindingDefinition<TContext>[],
): ShortcutEventPolicyResult {
  return {
    preventDefault: bindings.some((binding) => binding.preventDefault ?? false),
    stopPropagation: bindings.some((binding) => binding.stopPropagation ?? false),
  };
}

function isPromiseLike<T>(value: unknown): value is PromiseLike<T> {
  if (!value) {
    return false;
  }
  if (typeof value !== "object" && typeof value !== "function") {
    return false;
  }
  return typeof (value as PromiseLike<T>).then === "function";
}

function isAbortError(error: unknown): boolean {
  if (error instanceof Error && error.name === "AbortError") {
    return true;
  }
  return false;
}

function toCommandExecutionResult(result: CommandResult): ShortcutCommandExecutionResult {
  if (result.ok) {
    return {
      status: "ok",
      message: result.message,
      errorCode: result.errorCode,
    };
  }

  return {
    status: "rejected",
    message: result.message,
    errorCode: result.errorCode,
  };
}

function toCommandExecutionError(error: unknown): ShortcutCommandExecutionResult {
  return {
    status: "error",
    message: error instanceof Error ? error.message : "Shortcut command threw",
    errorCode: "shortcut-command-threw",
  };
}

function toCancelledResult(reason?: string): ShortcutCommandExecutionResult {
  return {
    status: "cancelled",
    message: reason ?? "Shortcut command cancelled",
    errorCode: "shortcut-command-cancelled",
  };
}

function normalizeChordCancelStrokes(cancelKeys: readonly string[]): Set<string> {
  const normalized = new Set<string>();
  for (const rawKey of cancelKeys) {
    const stroke = parseStrokeToken(rawKey, "key");
    if (!stroke) {
      continue;
    }
    normalized.add(`${stroke.matchBy}:${stroke.stroke}`);
  }
  return normalized;
}

function chordCancelKey(stroke: NormalizedBindingStroke): string {
  return `${stroke.matchBy}:${stroke.stroke}`;
}

export function normalizeShortcutEvent(event: ShortcutKeyboardEvent): NormalizedShortcutEvent {
  const key = normalizeEventKey(event.key);
  const code = normalizeEventCode(event.code);
  const mod = event.metaKey || event.ctrlKey;

  return {
    key,
    code,
    mod,
    shift: event.shiftKey,
    alt: event.altKey,
    ctrl: event.ctrlKey,
    meta: event.metaKey,
    keyStroke: serializeStroke({
      mod,
      shift: event.shiftKey,
      alt: event.altKey,
      key,
    }),
    codeStroke: serializeStroke({
      mod,
      shift: event.shiftKey,
      alt: event.altKey,
      key: code,
    }),
  };
}

export class ShortcutResolver<TContext> {
  private readonly fallbackContexts: readonly ShortcutContextId[];
  private readonly bindingsByContext: Map<ShortcutContextId, PreparedBinding<TContext>[]>;
  private readonly chordTimeoutMs: number;
  private readonly chordCancelStrokes: Set<string>;

  private activeChord: ActiveChordState<TContext> | null = null;

  constructor(
    registry: ShortcutRegistryDefinition<TContext>,
    options: ShortcutResolverOptions = {},
  ) {
    assertValidShortcutRegistry(registry);

    this.fallbackContexts = buildFallbackContexts(options.fallbackContexts);
    this.chordTimeoutMs = options.chord?.timeoutMs ?? DEFAULT_CHORD_TIMEOUT_MS;
    this.chordCancelStrokes = normalizeChordCancelStrokes(
      options.chord?.cancelKeys ?? DEFAULT_CHORD_CANCEL_KEYS,
    );

    const commandById = new Map<CommandId, CommandDefinition<TContext>>();
    for (const command of registry.commands) {
      commandById.set(command.id, command);
    }

    this.bindingsByContext = new Map(
      SHORTCUT_CONTEXT_IDS.map((context) => [context, [] as PreparedBinding<TContext>[]]),
    );

    let order = 0;
    for (const [bindingIndex, binding] of registry.keybindings.entries()) {
      const command = commandById.get(binding.commandId);
      if (!command) {
        throw new Error(
          `Binding at index ${bindingIndex} references unknown command "${binding.commandId}"`,
        );
      }

      const sequences = binding.keys.map((key) => parseBindingSequence(binding, key));
      if (sequences.some((sequence) => sequence === null)) {
        throw new Error(
          `Binding at index ${bindingIndex} for command "${binding.commandId}" has invalid key format`,
        );
      }

      const contextBindings = this.bindingsByContext.get(binding.context);
      if (!contextBindings) {
        throw new Error(`Unknown shortcut context "${binding.context}"`);
      }

      contextBindings.push({
        order,
        binding,
        command,
        sequences: sequences as readonly NormalizedBindingSequence[],
      });
      order += 1;
    }
  }

  resolve(input: ShortcutResolveInput<TContext>): ShortcutResolution<TContext> {
    const nowMs = input.timestampMs ?? Date.now();
    const normalizedEvent = normalizeShortcutEvent(input.event);
    const contextPriority = buildContextPriority(input.activeContexts, this.fallbackContexts);

    const fromActiveChord = this.resolveFromActiveChord(normalizedEvent, contextPriority, nowMs);
    if (fromActiveChord && fromActiveChord.kind !== "chord-cancelled") {
      return fromActiveChord;
    }

    const freshResolution = this.resolveFresh(
      normalizedEvent,
      contextPriority,
      input.runtimeContext,
      nowMs,
    );
    if (freshResolution.kind !== "none") {
      return freshResolution;
    }

    return fromActiveChord ?? { kind: "none" };
  }

  getActiveChordState(): ShortcutChordStateSnapshot | null {
    if (!this.activeChord) {
      return null;
    }
    return this.toChordSnapshot(this.activeChord);
  }

  cancelActiveChord(): ShortcutResolutionChordCancelled | ShortcutResolutionNone {
    if (!this.activeChord) {
      return { kind: "none" };
    }
    this.activeChord = null;
    return {
      kind: "chord-cancelled",
      reason: "manual",
    };
  }

  private resolveFromActiveChord(
    normalizedEvent: NormalizedShortcutEvent,
    contextPriority: readonly ShortcutContextId[],
    nowMs: number,
  ): ShortcutResolution<TContext> | null {
    const chord = this.activeChord;
    if (!chord) {
      return null;
    }

    if (nowMs > chord.expiresAtMs) {
      this.activeChord = null;
      return {
        kind: "chord-cancelled",
        reason: "timeout",
      };
    }

    if (!contextPriority.includes(chord.context)) {
      this.activeChord = null;
      return {
        kind: "chord-cancelled",
        reason: "context-changed",
      };
    }

    const cancellationProbe: NormalizedBindingStroke[] = [
      {
        matchBy: "key",
        stroke: normalizedEvent.keyStroke,
        sourceStroke: normalizedEvent.keyStroke,
      },
      {
        matchBy: "code",
        stroke: normalizedEvent.codeStroke,
        sourceStroke: normalizedEvent.codeStroke,
      },
    ];
    for (const stroke of cancellationProbe) {
      if (this.chordCancelStrokes.has(chordCancelKey(stroke))) {
        this.activeChord = null;
        return {
          kind: "chord-cancelled",
          reason: "cancel-key",
        };
      }
    }

    const stepIndex = chord.nextStepIndex;
    const matchingCandidates = chord.candidates
      .filter((candidate) => {
        const nextStroke = candidate.sequence.strokes[stepIndex];
        if (!nextStroke) {
          return false;
        }
        return matchesStroke(nextStroke, normalizedEvent);
      })
      .sort((left, right) => left.entry.order - right.entry.order);

    if (matchingCandidates.length === 0) {
      this.activeChord = null;
      return {
        kind: "chord-cancelled",
        reason: "mismatch",
      };
    }

    const completed = matchingCandidates.find((candidate) => {
      return candidate.sequence.strokes.length === stepIndex + 1;
    });

    const matchedStroke = matchingCandidates[0]?.sequence.strokes[stepIndex]?.sourceStroke ?? "";
    const nextMatchedStrokes = [...chord.matchedStrokes, matchedStroke];

    if (completed) {
      const result: ShortcutResolutionCommand<TContext> = {
        kind: "command",
        fromChord: true,
        eventPolicy: eventPolicyFromBinding(completed.entry.binding),
        chord: {
          context: chord.context,
          commandIds: [completed.entry.command.id],
          startedAtMs: chord.startedAtMs,
          expiresAtMs: chord.expiresAtMs,
          matchedStrokes: nextMatchedStrokes,
          remainingSteps: 0,
        },
        match: {
          commandId: completed.entry.command.id,
          context: chord.context,
          command: completed.entry.command,
          binding: completed.entry.binding,
          normalizedEvent,
          matchedStroke: completed.sequence.sourceSequence,
          matchBy: completed.sequence.strokes[stepIndex]?.matchBy ?? "key",
        },
      };
      this.activeChord = null;
      return result;
    }

    this.activeChord = {
      context: chord.context,
      candidates: matchingCandidates,
      nextStepIndex: stepIndex + 1,
      startedAtMs: chord.startedAtMs,
      expiresAtMs: nowMs + this.chordTimeoutMs,
      matchedStrokes: nextMatchedStrokes,
    };

    return {
      kind: "chord-awaiting",
      state: this.toChordSnapshot(this.activeChord),
      eventPolicy: mergeEventPolicy(matchingCandidates.map((candidate) => candidate.entry.binding)),
    };
  }

  private resolveFresh(
    normalizedEvent: NormalizedShortcutEvent,
    contextPriority: readonly ShortcutContextId[],
    runtimeContext: TContext,
    nowMs: number,
  ): ShortcutResolution<TContext> {
    for (const context of contextPriority) {
      const contextBindings = this.bindingsByContext.get(context);
      if (!contextBindings || contextBindings.length === 0) {
        continue;
      }

      const chordCandidates: ChordCandidate<TContext>[] = [];
      for (const entry of contextBindings) {
        if (entry.binding.when && !entry.binding.when(runtimeContext)) {
          continue;
        }

        for (const sequence of entry.sequences) {
          const firstStroke = sequence.strokes[0];
          if (!firstStroke || !matchesStroke(firstStroke, normalizedEvent)) {
            continue;
          }

          if (sequence.strokes.length === 1) {
            return {
              kind: "command",
              fromChord: false,
              eventPolicy: eventPolicyFromBinding(entry.binding),
              match: {
                commandId: entry.command.id,
                context,
                command: entry.command,
                binding: entry.binding,
                normalizedEvent,
                matchedStroke: sequence.sourceSequence,
                matchBy: firstStroke.matchBy,
              },
            };
          }

          chordCandidates.push({
            entry,
            sequence,
          });
        }
      }

      if (chordCandidates.length > 0) {
        const firstStroke = chordCandidates[0]?.sequence.strokes[0]?.sourceStroke ?? "";
        this.activeChord = {
          context,
          candidates: chordCandidates,
          nextStepIndex: 1,
          startedAtMs: nowMs,
          expiresAtMs: nowMs + this.chordTimeoutMs,
          matchedStrokes: [firstStroke],
        };

        return {
          kind: "chord-awaiting",
          state: this.toChordSnapshot(this.activeChord),
          eventPolicy: mergeEventPolicy(
            chordCandidates.map((candidate) => candidate.entry.binding),
          ),
        };
      }
    }

    return {
      kind: "none",
    };
  }

  private toChordSnapshot(chord: ActiveChordState<TContext>): ShortcutChordStateSnapshot {
    const commandIds = [
      ...new Set(chord.candidates.map((candidate) => candidate.entry.command.id)),
    ];
    const remainingSteps =
      chord.candidates.length > 0
        ? Math.max(
            0,
            Math.min(
              ...chord.candidates.map(
                (candidate) => candidate.sequence.strokes.length - chord.nextStepIndex,
              ),
            ),
          )
        : 0;

    return {
      context: chord.context,
      commandIds,
      startedAtMs: chord.startedAtMs,
      expiresAtMs: chord.expiresAtMs,
      matchedStrokes: [...chord.matchedStrokes],
      remainingSteps,
    };
  }
}

export function applyBindingEventPolicy<TContext>(
  event: ShortcutKeyboardEvent,
  binding: KeybindingDefinition<TContext>,
): ShortcutEventPolicyResult {
  const policy = eventPolicyFromBinding(binding);
  if (policy.preventDefault) {
    event.preventDefault?.();
  }
  if (policy.stopPropagation) {
    event.stopPropagation?.();
  }
  return policy;
}

export function applyResolutionEventPolicy<TContext>(
  event: ShortcutKeyboardEvent,
  resolution: ShortcutResolution<TContext>,
): ShortcutEventPolicyResult {
  if (resolution.kind === "command") {
    return applyBindingEventPolicy(event, resolution.match.binding);
  }

  if (resolution.kind === "chord-awaiting") {
    if (resolution.eventPolicy.preventDefault) {
      event.preventDefault?.();
    }
    if (resolution.eventPolicy.stopPropagation) {
      event.stopPropagation?.();
    }
    return resolution.eventPolicy;
  }

  return {
    preventDefault: false,
    stopPropagation: false,
  };
}

export function startShortcutCommandExecution<TContext>(
  command: CommandDefinition<TContext>,
  context: TContext,
): ShortcutCommandExecutionHandle {
  const abortController = new AbortController();
  const executionContext: CommandExecutionContext = {
    signal: abortController.signal,
  };

  let pending = true;
  let cancelled = false;
  let cancelledMessage = "Shortcut command cancelled";

  const cancel = (reason?: string): void => {
    if (!pending || cancelled) {
      return;
    }
    cancelled = true;
    cancelledMessage = reason ?? cancelledMessage;
    abortController.abort();
  };

  let runOutput: CommandResult | Promise<CommandResult>;
  try {
    runOutput = command.run(context, executionContext);
  } catch (error) {
    const immediateError = toCommandExecutionError(error);
    pending = false;
    return {
      initial: immediateError,
      completion: Promise.resolve(immediateError),
      cancel,
      isPending: () => false,
    };
  }

  if (!isPromiseLike<CommandResult>(runOutput)) {
    pending = false;
    const immediateResult = cancelled
      ? toCancelledResult(cancelledMessage)
      : toCommandExecutionResult(runOutput);
    return {
      initial: immediateResult,
      completion: Promise.resolve(immediateResult),
      cancel,
      isPending: () => false,
    };
  }

  const completion = Promise.resolve(runOutput)
    .then((result) => {
      if (cancelled || abortController.signal.aborted) {
        return toCancelledResult(cancelledMessage);
      }
      return toCommandExecutionResult(result);
    })
    .catch((error) => {
      if (cancelled || abortController.signal.aborted || isAbortError(error)) {
        return toCancelledResult(cancelledMessage);
      }
      return toCommandExecutionError(error);
    })
    .then((result) => {
      pending = false;
      return result;
    });

  return {
    initial: {
      status: "pending",
    },
    completion,
    cancel,
    isPending: () => pending,
  };
}

export async function executeShortcutCommand<TContext>(
  command: CommandDefinition<TContext>,
  context: TContext,
): Promise<ShortcutCommandExecutionResult> {
  const execution = startShortcutCommandExecution(command, context);
  return execution.completion;
}

export async function executeShortcutMatch<TContext>(
  resolution: ShortcutResolutionCommand<TContext>,
  context: TContext,
): Promise<ShortcutCommandExecutionResult> {
  return executeShortcutCommand(resolution.match.command, context);
}
