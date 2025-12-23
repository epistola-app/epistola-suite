// Re-export @tiptap/react (without re-exporting @tiptap/core to avoid duplication)
import * as TipTapReact from '@tiptap/react';

// Export only the React-specific parts, not the re-exports from @tiptap/core
export const {
  EditorConsumer,
  EditorContent,
  EditorContext,
  EditorProvider,
  MarkViewContent,
  NodeViewContent,
  NodeViewWrapper,
  PureEditorContent,
  ReactMarkView,
  ReactMarkViewContext,
  ReactMarkViewRenderer,
  ReactNodeView,
  ReactNodeViewContentProvider,
  ReactNodeViewContext,
  ReactNodeViewRenderer,
  ReactRenderer,
  useCurrentEditor,
  useEditor,
  useEditorState,
  useReactNodeView,
} = TipTapReact;
