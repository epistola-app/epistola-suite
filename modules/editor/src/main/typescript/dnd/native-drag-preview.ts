import type { ElementEventPayloadMap } from '@atlaskit/pragmatic-drag-and-drop/element/adapter'
import { preserveOffsetOnSource } from '@atlaskit/pragmatic-drag-and-drop/element/preserve-offset-on-source'
import { setCustomNativeDragPreview } from '@atlaskit/pragmatic-drag-and-drop/element/set-custom-native-drag-preview'

type PreviewIntent = 'insert' | 'move'

export function setEditorDragPreview({
  nativeSetDragImage,
  sourceElement,
  input,
  label,
  intent,
}: {
  nativeSetDragImage: ElementEventPayloadMap['onGenerateDragPreview']['nativeSetDragImage']
  sourceElement: HTMLElement
  input: ElementEventPayloadMap['onGenerateDragPreview']['location']['current']['input']
  label: string
  intent: PreviewIntent
}): void {
  setCustomNativeDragPreview({
    nativeSetDragImage,
    getOffset: preserveOffsetOnSource({ element: sourceElement, input }),
    render: ({ container }) => {
      const previewEl = createPreviewElement(intent, label)
      container.appendChild(previewEl)
      return () => previewEl.remove()
    },
  })
}

function createPreviewElement(intent: PreviewIntent, label: string): HTMLElement {
  const wrapper = document.createElement('div')
  wrapper.style.boxSizing = 'border-box'
  wrapper.style.minWidth = '190px'
  wrapper.style.maxWidth = '260px'
  wrapper.style.border = '1px solid color-mix(in srgb, var(--ep-blue-400) 60%, var(--ep-gray-300))'
  wrapper.style.borderRadius = 'var(--ep-radius-sm)'
  wrapper.style.background = 'color-mix(in srgb, var(--ep-blue-50) 80%, var(--ep-white))'
  wrapper.style.boxShadow = 'var(--ep-shadow-md)'
  wrapper.style.color = 'var(--ep-gray-900)'
  wrapper.style.overflow = 'hidden'

  const header = document.createElement('div')
  header.textContent = intent === 'insert' ? 'Insert block' : 'Move block'
  header.style.padding = 'var(--ep-space-1) var(--ep-space-2)'
  header.style.fontFamily = 'var(--ep-font-sans)'
  header.style.fontSize = '10px'
  header.style.fontWeight = '600'
  header.style.letterSpacing = '0.04em'
  header.style.textTransform = 'uppercase'
  header.style.color = 'var(--ep-blue-700)'
  header.style.background = 'color-mix(in srgb, var(--ep-blue-100) 70%, var(--ep-white))'
  header.style.borderBottom = '1px solid color-mix(in srgb, var(--ep-blue-300) 45%, transparent)'

  const title = document.createElement('div')
  title.textContent = label
  title.style.padding = 'var(--ep-space-2)'
  title.style.fontFamily = 'var(--ep-font-sans)'
  title.style.fontSize = 'var(--ep-text-sm)'
  title.style.fontWeight = '600'
  title.style.lineHeight = '1.2'
  title.style.whiteSpace = 'nowrap'
  title.style.overflow = 'hidden'
  title.style.textOverflow = 'ellipsis'

  wrapper.append(header, title)
  return wrapper
}
