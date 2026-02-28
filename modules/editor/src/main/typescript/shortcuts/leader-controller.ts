import type { ShortcutChordCancelReason, ShortcutCommandExecutionResult } from './resolver.js'
import type { CommandId } from './foundation.js'

export type LeaderStatus = 'idle' | 'success' | 'error'

export interface LeaderModeState {
  visible: boolean
  status: LeaderStatus
  message: string
}

export interface LeaderModeTimingConfig {
  idleHideMs: number
  resultHideMs: number
  messageClearMs: number
}

export interface LeaderModeControllerOptions {
  timing: LeaderModeTimingConfig
  getIdleTokens: (commandIds: readonly CommandId[]) => string[]
  fallbackTokens: readonly string[]
  onStateChange: (state: LeaderModeState) => void
  cancelActiveChord: () => void
  blurEditingTarget?: () => void
}

const INITIAL_STATE: LeaderModeState = {
  visible: false,
  status: 'idle',
  message: '',
}

export class LeaderModeController {
  private _state: LeaderModeState = { ...INITIAL_STATE }
  private _idleTimeout: ReturnType<typeof setTimeout> | null = null
  private _resultTimeout: ReturnType<typeof setTimeout> | null = null
  private _clearTimeout: ReturnType<typeof setTimeout> | null = null

  constructor(private readonly _options: LeaderModeControllerOptions) {}

  get state(): LeaderModeState {
    return this._state
  }

  showAwaiting(commandIds: readonly CommandId[]): void {
    this._clearTimers()
    this._options.blurEditingTarget?.()

    const idleTokens = this._options.getIdleTokens(commandIds)
    const tokens = idleTokens.length > 0 ? idleTokens : this._options.fallbackTokens

    this._setState({
      visible: true,
      status: 'idle',
      message: `Waiting: ${tokens.join(' ')}`,
    })

    this._idleTimeout = setTimeout(() => {
      this._options.cancelActiveChord()
      this._hide()
    }, this._options.timing.idleHideMs)
  }

  handleChordCancelled(reason: ShortcutChordCancelReason): void {
    if (reason === 'mismatch') {
      this._showResult(false, 'Unknown leader command')
      return
    }
    this._hide()
  }

  handleCommandExecution(
    initial: ShortcutCommandExecutionResult,
    completion: Promise<ShortcutCommandExecutionResult>,
  ): void {
    if (initial.status !== 'pending') {
      this._showCommandResult(initial)
      return
    }

    this._clearTimers()
    this._setState({
      visible: true,
      status: 'idle',
      message: 'Running command...',
    })

    void completion.then((result) => {
      this._showCommandResult(result)
    })
  }

  dispose(): void {
    this._clearTimers()
  }

  private _showCommandResult(result: ShortcutCommandExecutionResult): void {
    if (result.status === 'cancelled') {
      this._hide()
      return
    }

    if (result.status === 'ok') {
      this._showResult(true, result.message ?? 'Done')
      return
    }

    const message = result.message ?? (result.status === 'rejected' ? 'Command rejected' : 'Command failed')
    this._showResult(false, message)
  }

  private _showResult(ok: boolean, message: string): void {
    this._clearTimers()
    this._setState({
      visible: true,
      status: ok ? 'success' : 'error',
      message,
    })
    this._resultTimeout = setTimeout(() => this._hide(), this._options.timing.resultHideMs)
  }

  private _hide(): void {
    this._clearTimers()
    this._setState({
      visible: false,
      status: this._state.status,
      message: this._state.message,
    })
    this._clearTimeout = setTimeout(() => {
      this._setState({
        visible: false,
        status: 'idle',
        message: '',
      })
    }, this._options.timing.messageClearMs)
  }

  private _setState(state: LeaderModeState): void {
    this._state = state
    this._options.onStateChange(state)
  }

  private _clearTimers(): void {
    if (this._idleTimeout) {
      clearTimeout(this._idleTimeout)
      this._idleTimeout = null
    }
    if (this._resultTimeout) {
      clearTimeout(this._resultTimeout)
      this._resultTimeout = null
    }
    if (this._clearTimeout) {
      clearTimeout(this._clearTimeout)
      this._clearTimeout = null
    }
  }
}
