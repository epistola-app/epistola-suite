/**
 * Observable state container - framework-agnostic state management.
 *
 * Simple pub/sub pattern that can be used from vanilla JS, React, Vue, or any framework.
 * No React hooks required - just subscribe and get notified of changes.
 */

/**
 * Listener function called when state changes.
 */
export type StateListener<T> = (state: T, previousState: T) => void;

/**
 * Key listener function called when a specific key changes.
 */
export type KeyListener<T, K extends keyof T> = (
  value: T[K],
  previousValue: T[K],
) => void;

/**
 * Unsubscribe function returned by subscribe methods.
 */
export type Unsubscribe = () => void;

/**
 * State update can be a partial object or a function that returns a partial object.
 */
export type StateUpdate<T> = Partial<T> | ((state: T) => Partial<T>);

/**
 * Observable state container.
 */
export interface StateContainer<T> {
  /**
   * Get the current state (read-only snapshot).
   */
  getState(): Readonly<T>;

  /**
   * Update the state with a partial object or updater function.
   * Notifies all subscribers after the update.
   */
  setState(update: StateUpdate<T>): void;

  /**
   * Replace the entire state.
   * Notifies all subscribers after the replacement.
   */
  replaceState(newState: T): void;

  /**
   * Subscribe to all state changes.
   * Returns an unsubscribe function.
   */
  subscribe(listener: StateListener<T>): Unsubscribe;

  /**
   * Subscribe to changes on a specific key.
   * Only notifies when that key's value changes (shallow equality).
   * Returns an unsubscribe function.
   */
  subscribeKey<K extends keyof T>(key: K, listener: KeyListener<T, K>): Unsubscribe;

  /**
   * Get a snapshot for dirty checking or comparison.
   * Returns a deep clone of the current state.
   */
  snapshot(): T;

  /**
   * Batch multiple updates into a single notification.
   * Useful for performance when making multiple related changes.
   */
  batch(updates: () => void): void;
}

/**
 * Create an observable state container.
 *
 * @param initialState The initial state
 * @returns A state container with subscribe, getState, and setState methods
 *
 * @example
 * ```typescript
 * const store = createState({ count: 0, name: 'Test' });
 *
 * // Subscribe to all changes
 * const unsubscribe = store.subscribe((state, prev) => {
 *   console.log('State changed:', prev, '->', state);
 * });
 *
 * // Subscribe to specific key
 * store.subscribeKey('count', (value, prev) => {
 *   console.log('Count changed:', prev, '->', value);
 * });
 *
 * // Update state
 * store.setState({ count: 1 });
 * store.setState((s) => ({ count: s.count + 1 }));
 *
 * // Cleanup
 * unsubscribe();
 * ```
 */
export function createState<T extends object>(initialState: T): StateContainer<T> {
  let state: T = { ...initialState };
  const listeners = new Set<StateListener<T>>();
  const keyListeners = new Map<keyof T, Set<KeyListener<T, keyof T>>>();
  let isBatching = false;
  let pendingNotification: { prev: T; current: T } | null = null;

  /**
   * Deep clone the state for snapshots.
   */
  function deepClone(obj: T): T {
    return JSON.parse(JSON.stringify(obj));
  }

  /**
   * Notify all listeners of a state change.
   */
  function notifyListeners(previousState: T, currentState: T): void {
    // Notify global listeners
    for (const listener of listeners) {
      try {
        listener(currentState, previousState);
      } catch (error) {
        console.error("Error in state listener:", error);
      }
    }

    // Notify key-specific listeners
    for (const [key, keyListenerSet] of keyListeners) {
      const prevValue = previousState[key];
      const currValue = currentState[key];

      // Shallow equality check
      if (prevValue !== currValue) {
        for (const listener of keyListenerSet) {
          try {
            listener(currValue, prevValue);
          } catch (error) {
            console.error(`Error in key listener for '${String(key)}':`, error);
          }
        }
      }
    }
  }

  return {
    getState(): Readonly<T> {
      return state;
    },

    setState(update: StateUpdate<T>): void {
      const previousState = state;
      const partial = typeof update === "function" ? update(state) : update;

      // Create new state object with updates
      state = { ...state, ...partial };

      // Handle batching
      if (isBatching) {
        pendingNotification = {
          prev: pendingNotification?.prev ?? previousState,
          current: state,
        };
      } else {
        notifyListeners(previousState, state);
      }
    },

    replaceState(newState: T): void {
      const previousState = state;
      state = { ...newState };

      if (isBatching) {
        pendingNotification = {
          prev: pendingNotification?.prev ?? previousState,
          current: state,
        };
      } else {
        notifyListeners(previousState, state);
      }
    },

    subscribe(listener: StateListener<T>): Unsubscribe {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },

    subscribeKey<K extends keyof T>(key: K, listener: KeyListener<T, K>): Unsubscribe {
      if (!keyListeners.has(key)) {
        keyListeners.set(key, new Set());
      }
      const listenerSet = keyListeners.get(key)!;
      // Type assertion needed due to Map's generic typing
      listenerSet.add(listener as KeyListener<T, keyof T>);

      return () => {
        listenerSet.delete(listener as KeyListener<T, keyof T>);
        if (listenerSet.size === 0) {
          keyListeners.delete(key);
        }
      };
    },

    snapshot(): T {
      return deepClone(state);
    },

    batch(updates: () => void): void {
      const previousState = state;
      isBatching = true;
      pendingNotification = null;

      try {
        updates();
      } finally {
        isBatching = false;

        // Only notify if there were actual changes
        if (pendingNotification) {
          notifyListeners(previousState, state);
        }
        pendingNotification = null;
      }
    },
  };
}

/**
 * Create a computed value that updates when dependencies change.
 *
 * @param store The state container to watch
 * @param selector A function that computes a derived value from state
 * @param onChange Callback when the computed value changes
 * @returns An object with get() method and unsubscribe function
 *
 * @example
 * ```typescript
 * const derived = createComputed(
 *   store,
 *   (state) => state.items.filter(i => i.active),
 *   (active) => console.log('Active items:', active)
 * );
 *
 * console.log(derived.get()); // Get current computed value
 * derived.unsubscribe();       // Stop watching
 * ```
 */
export function createComputed<T extends object, R>(
  store: StateContainer<T>,
  selector: (state: T) => R,
  onChange?: (value: R, previousValue: R | undefined) => void,
): { get: () => R; unsubscribe: Unsubscribe } {
  let cachedValue: R = selector(store.getState());
  let previousValue: R | undefined = undefined;

  const unsubscribe = store.subscribe((state) => {
    const newValue = selector(state);
    // Simple equality check - works for primitives and same references
    if (newValue !== cachedValue) {
      previousValue = cachedValue;
      cachedValue = newValue;
      onChange?.(cachedValue, previousValue);
    }
  });

  return {
    get: () => cachedValue,
    unsubscribe,
  };
}
