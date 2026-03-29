import { describe, expect, it } from 'vitest';
import { defineShortcutRegistry, type CommandDefinition } from './foundation.js';
import {
  ShortcutResolver,
  applyBindingEventPolicy,
  applyResolutionEventPolicy,
  executeShortcutCommand,
  executeShortcutMatch,
  normalizeShortcutEvent,
  startShortcutCommandExecution,
  type ShortcutKeyboardEvent,
} from './resolver.js';

interface TestContext {
  mode?: string;
  calls?: string[];
}

function keyboardEvent(
  input: Partial<ShortcutKeyboardEvent> & Pick<ShortcutKeyboardEvent, 'key' | 'code'>,
): ShortcutKeyboardEvent {
  return {
    key: input.key,
    code: input.code,
    ctrlKey: input.ctrlKey ?? false,
    metaKey: input.metaKey ?? false,
    shiftKey: input.shiftKey ?? false,
    altKey: input.altKey ?? false,
    preventDefault: input.preventDefault,
    stopPropagation: input.stopPropagation,
  };
}

function command(
  id: CommandDefinition<TestContext>['id'],
  label: string,
): CommandDefinition<TestContext> {
  return {
    id,
    label,
    category: 'Core',
    run: () => ({ ok: true }),
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return {
    promise,
    resolve,
    reject,
  };
}

describe('shortcut resolver', () => {
  it('normalizes key and code strokes with cross-platform mod', () => {
    const normalized = normalizeShortcutEvent(
      keyboardEvent({
        key: 'S',
        code: 'KeyS',
        ctrlKey: true,
        shiftKey: true,
      }),
    );

    expect(normalized.key).toBe('s');
    expect(normalized.code).toBe('keys');
    expect(normalized.keyStroke).toBe('mod+shift+s');
    expect(normalized.codeStroke).toBe('mod+shift+keys');
  });

  it('prioritizes active context over fallback contexts', () => {
    const resolver = new ShortcutResolver(
      defineShortcutRegistry({
        commands: [
          command('editor.dismiss.global', 'Dismiss global'),
          command('insertDialog.close', 'Close insert dialog'),
        ],
        keybindings: [
          {
            commandId: 'editor.dismiss.global',
            context: 'global',
            keys: ['escape'],
          },
          {
            commandId: 'insertDialog.close',
            context: 'insertDialog',
            keys: ['escape'],
          },
        ],
      }),
    );

    const withActiveDialog = resolver.resolve({
      event: keyboardEvent({ key: 'Escape', code: 'Escape' }),
      activeContexts: ['insertDialog'],
      runtimeContext: {},
    });
    expect(withActiveDialog.kind).toBe('command');
    if (withActiveDialog.kind === 'command') {
      expect(withActiveDialog.match.commandId).toBe('insertDialog.close');
    }

    const fallbackOnly = resolver.resolve({
      event: keyboardEvent({ key: 'Escape', code: 'Escape' }),
      activeContexts: [],
      runtimeContext: {},
    });
    expect(fallbackOnly.kind).toBe('command');
    if (fallbackOnly.kind === 'command') {
      expect(fallbackOnly.match.commandId).toBe('editor.dismiss.global');
    }
  });

  it('evaluates when predicates and keeps resolver deterministic by binding order', () => {
    const resolver = new ShortcutResolver(
      defineShortcutRegistry({
        commands: [
          command('editor.action.first', 'First action'),
          command('editor.action.second', 'Second action'),
        ],
        keybindings: [
          {
            commandId: 'editor.action.first',
            context: 'global',
            keys: ['x'],
            when: (ctx) => (ctx as TestContext).mode === 'alpha',
          },
          {
            commandId: 'editor.action.second',
            context: 'global',
            keys: ['x'],
            when: (ctx) => (ctx as TestContext).mode === 'beta',
          },
        ],
      }),
    );

    const betaMatch = resolver.resolve({
      event: keyboardEvent({ key: 'x', code: 'KeyX' }),
      activeContexts: [],
      runtimeContext: { mode: 'beta' },
    });
    expect(betaMatch.kind).toBe('command');
    if (betaMatch.kind === 'command') {
      expect(betaMatch.match.commandId).toBe('editor.action.second');
    }

    const alphaMatch = resolver.resolve({
      event: keyboardEvent({ key: 'x', code: 'KeyX' }),
      activeContexts: [],
      runtimeContext: { mode: 'alpha' },
    });
    expect(alphaMatch.kind).toBe('command');
    if (alphaMatch.kind === 'command') {
      expect(alphaMatch.match.commandId).toBe('editor.action.first');
    }
  });

  it('supports explicit key and code matching policies', () => {
    const resolver = new ShortcutResolver(
      defineShortcutRegistry({
        commands: [
          command('editor.match.by-key', 'Key match command'),
          command('editor.match.by-code', 'Code match command'),
        ],
        keybindings: [
          {
            commandId: 'editor.match.by-key',
            context: 'global',
            keys: ['z'],
            matchBy: 'key',
          },
          {
            commandId: 'editor.match.by-code',
            context: 'editor',
            keys: ['keyy'],
            matchBy: 'code',
          },
        ],
      }),
      { fallbackContexts: ['global', 'editor'] },
    );

    const event = keyboardEvent({ key: 'z', code: 'KeyY' });

    const keyMatch = resolver.resolve({
      event,
      activeContexts: ['global'],
      runtimeContext: {},
    });
    expect(keyMatch.kind).toBe('command');
    if (keyMatch.kind === 'command') {
      expect(keyMatch.match.commandId).toBe('editor.match.by-key');
      expect(keyMatch.match.matchBy).toBe('key');
    }

    const codeMatch = resolver.resolve({
      event,
      activeContexts: ['editor'],
      runtimeContext: {},
    });
    expect(codeMatch.kind).toBe('command');
    if (codeMatch.kind === 'command') {
      expect(codeMatch.match.commandId).toBe('editor.match.by-code');
      expect(codeMatch.match.matchBy).toBe('code');
    }
  });

  it('handles chord progression and completion from one resolve API', () => {
    const resolver = new ShortcutResolver(
      defineShortcutRegistry({
        commands: [
          command('editor.preview.toggle', 'Toggle preview'),
          command('editor.help.open', 'Open help'),
        ],
        keybindings: [
          {
            commandId: 'editor.preview.toggle',
            context: 'global',
            keys: ['mod+space p'],
            preventDefault: true,
          },
          {
            commandId: 'editor.help.open',
            context: 'global',
            keys: ['mod+space /'],
            preventDefault: true,
          },
        ],
      }),
      {
        chord: {
          timeoutMs: 1000,
        },
      },
    );

    const start = resolver.resolve({
      event: keyboardEvent({ key: ' ', code: 'Space', ctrlKey: true }),
      activeContexts: ['global'],
      runtimeContext: {},
      timestampMs: 100,
    });
    expect(start.kind).toBe('chord-awaiting');
    if (start.kind === 'chord-awaiting') {
      expect(start.state.commandIds).toEqual(['editor.preview.toggle', 'editor.help.open']);
      expect(start.state.remainingSteps).toBe(1);
      expect(start.eventPolicy.preventDefault).toBe(true);
    }

    const complete = resolver.resolve({
      event: keyboardEvent({ key: 'p', code: 'KeyP' }),
      activeContexts: ['global'],
      runtimeContext: {},
      timestampMs: 400,
    });
    expect(complete.kind).toBe('command');
    if (complete.kind === 'command') {
      expect(complete.fromChord).toBe(true);
      expect(complete.match.commandId).toBe('editor.preview.toggle');
      expect(complete.chord?.remainingSteps).toBe(0);
    }
  });

  it('cancels chord on timeout, cancel key, and mismatch', () => {
    const resolver = new ShortcutResolver(
      defineShortcutRegistry({
        commands: [command('editor.preview.toggle', 'Toggle preview')],
        keybindings: [
          {
            commandId: 'editor.preview.toggle',
            context: 'global',
            keys: ['mod+space p'],
          },
        ],
      }),
      {
        chord: {
          timeoutMs: 200,
        },
      },
    );

    resolver.resolve({
      event: keyboardEvent({ key: ' ', code: 'Space', ctrlKey: true }),
      activeContexts: ['global'],
      runtimeContext: {},
      timestampMs: 100,
    });
    const timedOut = resolver.resolve({
      event: keyboardEvent({ key: 'p', code: 'KeyP' }),
      activeContexts: ['global'],
      runtimeContext: {},
      timestampMs: 500,
    });
    expect(timedOut).toEqual({ kind: 'chord-cancelled', reason: 'timeout' });

    resolver.resolve({
      event: keyboardEvent({ key: ' ', code: 'Space', ctrlKey: true }),
      activeContexts: ['global'],
      runtimeContext: {},
      timestampMs: 600,
    });
    const cancelledByEsc = resolver.resolve({
      event: keyboardEvent({ key: 'Escape', code: 'Escape' }),
      activeContexts: ['global'],
      runtimeContext: {},
      timestampMs: 650,
    });
    expect(cancelledByEsc).toEqual({
      kind: 'chord-cancelled',
      reason: 'cancel-key',
    });

    resolver.resolve({
      event: keyboardEvent({ key: ' ', code: 'Space', ctrlKey: true }),
      activeContexts: ['global'],
      runtimeContext: {},
      timestampMs: 700,
    });
    const mismatch = resolver.resolve({
      event: keyboardEvent({ key: 'x', code: 'KeyX' }),
      activeContexts: ['global'],
      runtimeContext: {},
      timestampMs: 710,
    });
    expect(mismatch).toEqual({ kind: 'chord-cancelled', reason: 'mismatch' });
  });

  it('falls back to fresh matching after chord mismatch', () => {
    const resolver = new ShortcutResolver(
      defineShortcutRegistry({
        commands: [
          command('editor.preview.toggle', 'Toggle preview'),
          command('editor.document.save', 'Save'),
        ],
        keybindings: [
          {
            commandId: 'editor.preview.toggle',
            context: 'global',
            keys: ['mod+space p'],
          },
          {
            commandId: 'editor.document.save',
            context: 'global',
            keys: ['mod+s'],
          },
        ],
      }),
    );

    resolver.resolve({
      event: keyboardEvent({ key: ' ', code: 'Space', ctrlKey: true }),
      activeContexts: ['global'],
      runtimeContext: {},
      timestampMs: 100,
    });

    const fallback = resolver.resolve({
      event: keyboardEvent({ key: 's', code: 'KeyS', ctrlKey: true }),
      activeContexts: ['global'],
      runtimeContext: {},
      timestampMs: 200,
    });

    expect(fallback.kind).toBe('command');
    if (fallback.kind === 'command') {
      expect(fallback.match.commandId).toBe('editor.document.save');
      expect(fallback.fromChord).toBe(false);
    }
  });

  it('fails fast on conflicting bindings through registry validation', () => {
    expect(() => {
      new ShortcutResolver(
        defineShortcutRegistry({
          commands: [
            command('editor.document.save', 'Save'),
            command('editor.document.submit', 'Submit'),
          ],
          keybindings: [
            {
              commandId: 'editor.document.save',
              context: 'global',
              keys: ['mod+s'],
            },
            {
              commandId: 'editor.document.submit',
              context: 'global',
              keys: ['mod+s'],
            },
          ],
        }),
      );
    }).toThrow('binding-conflict');
  });

  it('applies event policy from bindings and chord-awaiting resolutions', () => {
    let prevented = 0;
    let stopped = 0;

    const event = keyboardEvent({
      key: 's',
      code: 'KeyS',
      preventDefault: () => {
        prevented += 1;
      },
      stopPropagation: () => {
        stopped += 1;
      },
    });

    const bindingPolicy = applyBindingEventPolicy(event, {
      commandId: 'editor.document.save',
      context: 'global',
      keys: ['mod+s'],
      preventDefault: true,
      stopPropagation: true,
    });
    expect(bindingPolicy.preventDefault).toBe(true);
    expect(bindingPolicy.stopPropagation).toBe(true);
    expect(prevented).toBe(1);
    expect(stopped).toBe(1);

    const awaitingPolicy = applyResolutionEventPolicy(event, {
      kind: 'chord-awaiting',
      state: {
        context: 'global',
        commandIds: ['editor.preview.toggle'],
        startedAtMs: 0,
        expiresAtMs: 1000,
        matchedStrokes: ['mod+space'],
        remainingSteps: 1,
      },
      eventPolicy: {
        preventDefault: true,
        stopPropagation: false,
      },
    });
    expect(awaitingPolicy.preventDefault).toBe(true);
    expect(awaitingPolicy.stopPropagation).toBe(false);
    expect(prevented).toBe(2);
  });

  it('supports sync and async command execution with pending status and cancellation', async () => {
    const syncExecution = startShortcutCommandExecution(
      {
        id: 'editor.command.sync',
        label: 'Sync',
        category: 'Core',
        run: () => ({ ok: true, message: 'sync-ok' }),
      },
      {},
    );
    expect(syncExecution.initial.status).toBe('ok');
    expect(await syncExecution.completion).toEqual({
      status: 'ok',
      message: 'sync-ok',
      errorCode: undefined,
    });

    const asyncExecution = startShortcutCommandExecution(
      {
        id: 'editor.command.async',
        label: 'Async',
        category: 'Core',
        run: async () => ({ ok: true, message: 'async-ok' }),
      },
      {},
    );
    expect(asyncExecution.initial.status).toBe('pending');
    expect(asyncExecution.isPending()).toBe(true);
    const asyncResult = await asyncExecution.completion;
    expect(asyncResult.status).toBe('ok');
    expect(asyncExecution.isPending()).toBe(false);

    const gate = deferred<{ ok: boolean; message: string }>();
    const cancellableExecution = startShortcutCommandExecution(
      {
        id: 'editor.command.cancellable',
        label: 'Cancellable',
        category: 'Core',
        run: async (_context, execution) => {
          await gate.promise;
          if (execution.signal.aborted) {
            throw Object.assign(new Error('Aborted'), { name: 'AbortError' });
          }
          return { ok: true, message: 'late-ok' };
        },
      },
      {},
    );
    expect(cancellableExecution.initial.status).toBe('pending');
    cancellableExecution.cancel('Cancelled by test');
    gate.resolve({ ok: true, message: 'late-ok' });
    const cancelledResult = await cancellableExecution.completion;
    expect(cancelledResult.status).toBe('cancelled');
    expect(cancelledResult.errorCode).toBe('shortcut-command-cancelled');
  });

  it('returns execution statuses for rejected and thrown commands', async () => {
    const rejected = await executeShortcutCommand(
      {
        id: 'editor.command.rejected',
        label: 'Rejected',
        category: 'Core',
        run: () => ({ ok: false, message: 'blocked', errorCode: 'blocked' }),
      },
      {},
    );
    expect(rejected.status).toBe('rejected');
    expect(rejected.errorCode).toBe('blocked');

    const errored = await executeShortcutCommand(
      {
        id: 'editor.command.error',
        label: 'Error',
        category: 'Core',
        run: () => {
          throw new Error('boom');
        },
      },
      {},
    );
    expect(errored.status).toBe('error');
    expect(errored.errorCode).toBe('shortcut-command-threw');
    expect(errored.message).toContain('boom');
  });

  it('executes command resolutions via executeShortcutMatch', async () => {
    const calls: string[] = [];

    const resolver = new ShortcutResolver(
      defineShortcutRegistry<{ calls: string[] }>({
        commands: [
          {
            id: 'editor.preview.toggle',
            label: 'Toggle preview',
            category: 'Leader',
            run: ({ calls: targetCalls }) => {
              targetCalls.push('toggle-preview');
              return { ok: true };
            },
          },
        ],
        keybindings: [
          {
            commandId: 'editor.preview.toggle',
            context: 'global',
            keys: ['p'],
          },
        ],
      }),
    );

    const resolution = resolver.resolve({
      event: keyboardEvent({ key: 'p', code: 'KeyP' }),
      activeContexts: [],
      runtimeContext: {},
    });

    expect(resolution.kind).toBe('command');
    if (resolution.kind !== 'command') {
      throw new Error('Expected command resolution');
    }

    const result = await executeShortcutMatch(resolution, { calls });
    expect(result.status).toBe('ok');
    expect(calls).toEqual(['toggle-preview']);
  });

  it('fails fast when a binding key format is invalid', () => {
    expect(() => {
      new ShortcutResolver(
        defineShortcutRegistry({
          commands: [command('editor.document.save', 'Save')],
          keybindings: [
            {
              commandId: 'editor.document.save',
              context: 'global',
              keys: ['mod+shift'],
            },
          ],
        }),
      );
    }).toThrow('invalid key format');
  });
});
