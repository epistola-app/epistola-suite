import { useEditor, EditorContent } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import Underline from '@tiptap/extension-underline';
import { useEffect } from 'react';
import type { TextBlock } from '../../types/template';
import { useEditorStore } from '../../store/editorStore';
import { ExpressionNode } from './ExpressionNode';
import type { Editor } from '@tiptap/react';

interface TextBlockProps {
  block: TextBlock;
  isSelected?: boolean;
}

interface ToolbarButtonProps {
  onClick: () => void;
  isActive?: boolean;
  title: string;
  children: React.ReactNode;
}

function ToolbarButton({ onClick, isActive, title, children }: ToolbarButtonProps) {
  return (
    <button
      onClick={(e) => {
        e.preventDefault();
        e.stopPropagation();
        onClick();
      }}
      className={`
        px-2 py-1 text-sm rounded transition-colors
        ${isActive
          ? 'bg-blue-500 text-white'
          : 'bg-gray-100 text-gray-700 hover:bg-gray-200'}
      `}
      title={title}
    >
      {children}
    </button>
  );
}

function FormattingToolbar({ editor }: { editor: Editor }) {
  return (
    <div className="flex items-center gap-1 px-3 py-2 border-b border-gray-100 flex-wrap">
      <ToolbarButton
        onClick={() => editor.chain().focus().toggleBold().run()}
        isActive={editor.isActive('bold')}
        title="Bold (Ctrl+B)"
      >
        <strong>B</strong>
      </ToolbarButton>
      <ToolbarButton
        onClick={() => editor.chain().focus().toggleItalic().run()}
        isActive={editor.isActive('italic')}
        title="Italic (Ctrl+I)"
      >
        <em>I</em>
      </ToolbarButton>
      <ToolbarButton
        onClick={() => editor.chain().focus().toggleUnderline().run()}
        isActive={editor.isActive('underline')}
        title="Underline (Ctrl+U)"
      >
        <span className="underline">U</span>
      </ToolbarButton>
      <ToolbarButton
        onClick={() => editor.chain().focus().toggleStrike().run()}
        isActive={editor.isActive('strike')}
        title="Strikethrough"
      >
        <span className="line-through">S</span>
      </ToolbarButton>

      <div className="w-px h-5 bg-gray-300 mx-1" />

      <ToolbarButton
        onClick={() => editor.chain().focus().toggleBulletList().run()}
        isActive={editor.isActive('bulletList')}
        title="Bullet List"
      >
        •
      </ToolbarButton>
      <ToolbarButton
        onClick={() => editor.chain().focus().toggleOrderedList().run()}
        isActive={editor.isActive('orderedList')}
        title="Numbered List"
      >
        1.
      </ToolbarButton>

      <div className="w-px h-5 bg-gray-300 mx-1" />

      <ToolbarButton
        onClick={() => editor.chain().focus().toggleHeading({ level: 1 }).run()}
        isActive={editor.isActive('heading', { level: 1 })}
        title="Heading 1"
      >
        H1
      </ToolbarButton>
      <ToolbarButton
        onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()}
        isActive={editor.isActive('heading', { level: 2 })}
        title="Heading 2"
      >
        H2
      </ToolbarButton>
      <ToolbarButton
        onClick={() => editor.chain().focus().toggleHeading({ level: 3 }).run()}
        isActive={editor.isActive('heading', { level: 3 })}
        title="Heading 3"
      >
        H3
      </ToolbarButton>

      <div className="flex-1" />

      <ToolbarButton
        onClick={() => editor.commands.insertExpression('')}
        title="Insert Expression"
      >
        {'{{'} + {'}}'}
      </ToolbarButton>
    </div>
  );
}

export function TextBlockComponent({ block, isSelected = false }: TextBlockProps) {
  const updateBlock = useEditorStore((s) => s.updateBlock);

  const editor = useEditor({
    extensions: [
      StarterKit,
      Underline,
      ExpressionNode,
    ],
    content: block.content,
    onUpdate: ({ editor }) => {
      updateBlock(block.id, { content: editor.getJSON() });
    },
    editorProps: {
      attributes: {
        class: 'prose prose-sm max-w-none focus:outline-none min-h-[2rem] p-3',
      },
    },
  });

  // Update editor content when block content changes externally
  useEffect(() => {
    if (editor && JSON.stringify(editor.getJSON()) !== JSON.stringify(block.content)) {
      editor.commands.setContent(block.content);
    }
  }, [block.content, editor]);

  return (
    <div className="bg-white rounded-lg">
      {isSelected && editor && <FormattingToolbar editor={editor} />}
      <div style={block.styles}>
        <EditorContent editor={editor} />
      </div>
      {isSelected && (
        <div className="text-xs text-gray-400 px-3 pb-2">
          Tip: Type {'{{expression}}'} to insert a placeholder
        </div>
      )}
    </div>
  );
}
