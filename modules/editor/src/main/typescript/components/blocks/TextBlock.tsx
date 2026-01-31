import type {Editor} from "@tiptap/react";
import {EditorContent, useEditor} from "@tiptap/react";
import {BubbleMenu} from "@tiptap/react/menus";
import StarterKit from "@tiptap/starter-kit";
import Underline from "@tiptap/extension-underline";
import {useEffect} from "react";
import {
    Bold,
    Braces,
    Heading1,
    Heading2,
    Heading3,
    Italic,
    List,
    ListOrdered,
    Strikethrough,
    Underline as UnderlineIcon,
} from "lucide-react";
import type {TextBlock} from "../../types/template";
import {useEditorStore} from "../../store/editorStore";
import {ExpressionNode} from "./ExpressionNode";
import {BlockHeader} from "./BlockHeader";
import {Button} from "@/components/ui/button";
import {Tooltip, TooltipContent, TooltipTrigger} from "@/components/ui/tooltip";
import {Separator} from "@/components/ui/separator";

interface TextBlockProps {
  block: TextBlock;
  isSelected?: boolean;
  dragAttributes?: React.HTMLAttributes<HTMLDivElement>;
  dragListeners?: React.HTMLAttributes<HTMLDivElement>;
  onDelete?: (e: React.MouseEvent) => void;
}

interface FormattingButtonProps {
  onClick: () => void;
  isActive?: boolean;
  tooltip: string;
  icon: React.ReactNode;
}

function FormattingButton({ onClick, isActive, tooltip, icon }: FormattingButtonProps) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          variant={isActive ? "default" : "ghost"}
          size="icon-sm"
          onClick={(e) => {
            e.preventDefault();
            e.stopPropagation();
            onClick();
          }}
          aria-label={tooltip}
        >
          {icon}
        </Button>
      </TooltipTrigger>
      <TooltipContent side="top" sideOffset={8}>
        {tooltip}
      </TooltipContent>
    </Tooltip>
  );
}

function FloatingToolbar({ editor }: { editor: Editor }) {
  return (
    <BubbleMenu
      editor={editor}
      options={{
        placement: "top",
        strategy: "absolute",
      }}
      className="flex items-center gap-1 p-1.5 bg-white rounded-lg border border-slate-200 shadow-lg"
    >
      {/* Text formatting group */}
      <FormattingButton
        onClick={() => editor.chain().focus().toggleBold().run()}
        isActive={editor.isActive("bold")}
        tooltip="Bold (Ctrl+B)"
        icon={<Bold className="size-4" />}
      />
      <FormattingButton
        onClick={() => editor.chain().focus().toggleItalic().run()}
        isActive={editor.isActive("italic")}
        tooltip="Italic (Ctrl+I)"
        icon={<Italic className="size-4" />}
      />
      <FormattingButton
        onClick={() => editor.chain().focus().toggleUnderline().run()}
        isActive={editor.isActive("underline")}
        tooltip="Underline (Ctrl+U)"
        icon={<UnderlineIcon className="size-4" />}
      />
      <FormattingButton
        onClick={() => editor.chain().focus().toggleStrike().run()}
        isActive={editor.isActive("strike")}
        tooltip="Strikethrough"
        icon={<Strikethrough className="size-4" />}
      />

      <Separator orientation="vertical" className="h-6 mx-1" />

      {/* List group */}
      <FormattingButton
        onClick={() => editor.chain().focus().toggleBulletList().run()}
        isActive={editor.isActive("bulletList")}
        tooltip="Bullet List"
        icon={<List className="size-4" />}
      />
      <FormattingButton
        onClick={() => editor.chain().focus().toggleOrderedList().run()}
        isActive={editor.isActive("orderedList")}
        tooltip="Numbered List"
        icon={<ListOrdered className="size-4" />}
      />

      <Separator orientation="vertical" className="h-6 mx-1" />

      {/* Heading group */}
      <FormattingButton
        onClick={() => editor.chain().focus().toggleHeading({ level: 1 }).run()}
        isActive={editor.isActive("heading", { level: 1 })}
        tooltip="Heading 1"
        icon={<Heading1 className="size-4" />}
      />
      <FormattingButton
        onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()}
        isActive={editor.isActive("heading", { level: 2 })}
        tooltip="Heading 2"
        icon={<Heading2 className="size-4" />}
      />
      <FormattingButton
        onClick={() => editor.chain().focus().toggleHeading({ level: 3 }).run()}
        isActive={editor.isActive("heading", { level: 3 })}
        tooltip="Heading 3"
        icon={<Heading3 className="size-4" />}
      />

      <Separator orientation="vertical" className="h-6 mx-1" />

      {/* Expression insert */}
      <FormattingButton
        onClick={() => editor.commands.insertExpression("")}
        tooltip="Insert Expression"
        icon={<Braces className="size-4" />}
      />
    </BubbleMenu>
  );
}

export function TextBlockComponent({
  block,
  isSelected = false,
  dragAttributes,
  dragListeners,
  onDelete,
}: TextBlockProps) {
  const updateBlock = useEditorStore((s) => s.updateBlock);

  const editor = useEditor({
    extensions: [StarterKit, Underline, ExpressionNode],
    content: block.content,
    onUpdate: ({ editor }) => {
      updateBlock(block.id, { content: editor.getJSON() });
    },
    editorProps: {
      attributes: {
        class: "prose prose-sm max-w-none focus:outline-none min-h-[2rem] p-3",
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
      <BlockHeader
        title="TEXT"
        isSelected={isSelected}
        dragAttributes={dragAttributes}
        dragListeners={dragListeners}
        onDelete={onDelete}
      />
      {/* Floating toolbar - appears on text selection */}
      {editor && <FloatingToolbar editor={editor} />}
      <div style={block.styles}>
        <EditorContent editor={editor} />
      </div>
      {isSelected && (
        <div className="text-xs text-gray-400 px-3 pb-2 font-mono tracking-tight font-normal">
          Tip: Type {"{{expression}}"} or select text for formatting
        </div>
      )}
    </div>
  );
}
