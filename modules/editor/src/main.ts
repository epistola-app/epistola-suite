export interface EditorOptions {
  container: HTMLElement | string
}

export class Editor {
  private container: HTMLElement

  constructor(options: EditorOptions) {
    const el = typeof options.container === 'string'
      ? document.querySelector<HTMLElement>(options.container)
      : options.container

    if (!el) {
      throw new Error('Editor container not found')
    }

    this.container = el
    this.init()
  }

  private init(): void {
    this.container.innerHTML = '<div class="epistola-editor">Editor initialized</div>'
  }

  destroy(): void {
    this.container.innerHTML = ''
  }
}

export function createEditor(options: EditorOptions): Editor {
  return new Editor(options)
}
