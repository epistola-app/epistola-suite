import {useDraggable} from "@dnd-kit/core";
import type {LucideIcon} from "lucide-react";
import {Box, ChevronDown, Loader2, Wrench} from "lucide-react";
import {useEffect, useState} from "react";
import {AnimatePresence, motion, type Transition} from "motion/react";
import {cn} from "@/lib/utils";
import {Button} from "../ui/button";
import {Tooltip, TooltipContent, TooltipTrigger} from "@/components/ui/tooltip";
import {
    Tabs,
    TabsContent,
    TabsContents,
    TabsList,
    TabsTrigger,
} from "@/components/ui/animate-ui/components/radix/tabs";
import {type BlockTypeConfig, blockTypes} from "./blockTypes";

// Storage key for persisting collapsed state
const STORAGE_KEY = "blockPalette-collapsed";

// Motion transition presets
const transitions = {
  spring: { type: "spring", stiffness: 300, damping: 30 } as Transition,
  springFast: { type: "spring", stiffness: 400, damping: 30 } as Transition,
  springSnappy: { type: "spring", stiffness: 300, damping: 25 } as Transition,
  fade: { duration: 0.2 } as Transition,
};

// Tab configuration
const tabs = [
  { value: "blocks", icon: Box, label: "Standard Blocks", blocks: blockTypes },
  { value: "custom", icon: Wrench, label: "Custom Blocks", blocks: [] as BlockTypeConfig[] },
] as const;

// Reusable icon tab trigger with tooltip
function IconTabTrigger({
  value,
  icon: Icon,
  label,
}: {
  value: string;
  icon: LucideIcon;
  label: string;
}) {
  return (
    <TabsTrigger value={value} className="px-1 size-full">
      <Tooltip delayDuration={300}>
        <TooltipTrigger asChild>
          <div className="flex items-center justify-center min-h-7">
            <Icon className="size-4 shrink-0" />
          </div>
        </TooltipTrigger>
        <TooltipContent side="right">{label}</TooltipContent>
      </Tooltip>
    </TabsTrigger>
  );
}

function DraggableBlockType({
  config,
  collapsed,
}: {
  config: BlockTypeConfig;
  collapsed: boolean;
}) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: `palette-${config.type}`,
    data: {
      type: "palette",
      blockType: config.type,
      createBlock: config.createBlock,
    },
  });

  const Icon = config.icon;

  return (
    <Tooltip delayDuration={500}>
      <TooltipTrigger asChild>
        <div
          ref={setNodeRef}
          {...listeners}
          {...attributes}
          className={cn(
            "group relative flex items-start gap-3 p-3 rounded-lg border border-slate-200",
            "bg-white hover:bg-linear-to-br hover:from-blue-50 hover:to-indigo-50",
            "hover:border-blue-300 hover:shadow-md cursor-grab active:cursor-grabbing",
            isDragging ? "opacity-40 scale-95 shadow-lg" : "hover:scale-[1.02]",
            "transition-all duration-200",
          )}
          aria-label={config.description}
        >
          <div className="flex items-center justify-center size-5 rounded-lg text-slate-600 group-hover:bg-transparent group-hover:text-blue-600 transition-colors mt-px">
            <Icon className="size-5" />
          </div>
          {!collapsed && (
            <div className="flex-1 min-w-0 text-start overflow-hidden select-none">
              <div className="text-sm font-medium text-slate-700 group-hover:text-slate-900 truncate">
                {config.label}
              </div>
              <div className="text-xs text-slate-500 truncate">{config.description}</div>
            </div>
          )}
        </div>
      </TooltipTrigger>
      <TooltipContent>{config.description}</TooltipContent>
    </Tooltip>
  );
}

function BlockGrid({ blocks, collapsed }: { blocks: BlockTypeConfig[]; collapsed: boolean }) {
  return (
    <div
      className={cn("gap-2 m-2", collapsed ? "flex items-center flex-wrap" : "grid grid-cols-3")}
    >
      {blocks.map((config) => (
        <DraggableBlockType key={config.type} config={config} collapsed={collapsed} />
      ))}

      {blocks.length === 0 && (
        <div className="text-sm text-slate-500 italic">
          <p>Coming soon...</p>
        </div>
      )}
    </div>
  );
}

export function BlockPalette() {
  const [collapsed, setCollapsed] = useState(true);
  const [isLoading, setIsLoading] = useState(true);

  // Read from localStorage on mount with intentional delay
  useEffect(() => {
    const timer = setTimeout(() => {
      const stored = localStorage.getItem(STORAGE_KEY);
      if (stored !== null) {
        setCollapsed(JSON.parse(stored));
      }
      setIsLoading(false);
    }, 500);

    return () => clearTimeout(timer);
  }, []);

  // Persist changes (skip while loading)
  useEffect(() => {
    if (!isLoading) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(collapsed));
    }
  }, [collapsed, isLoading]);

  if (isLoading) {
    return (
      <div className="relative border bg-white">
        <div className="flex items-center justify-between pt-2 px-2">
          <h3 className="text-xs font-semibold text-slate-600 uppercase tracking-wider">
            Block Library
          </h3>
        </div>
        <div className="flex items-center justify-center py-8">
          <Loader2 className="size-6 shrink-0 animate-spin text-slate-400" />
        </div>
      </div>
    );
  }

  return (
    <div className="relative border bg-white">
      {/* Header */}
      <div className="flex items-center justify-between pt-2 px-2">
        <h3 className="text-xs font-semibold text-slate-600 uppercase tracking-wider">
          Block Library
        </h3>
        <Button
          variant="ghost"
          size="icon-xs"
          onClick={() => setCollapsed((prev) => !prev)}
          className="text-slate-500 hover:text-slate-700 bg-white border z-10 absolute -bottom-3 left-1/2 -translate-x-1/2"
        >
          <motion.div
            animate={{ rotate: collapsed ? 0 : 180 }}
            transition={transitions.springSnappy}
          >
            <ChevronDown className="size-4 shrink-0" />
          </motion.div>
        </Button>
      </div>

      {/* Tabs Container */}
      <Tabs defaultValue="blocks" orientation="vertical" className="flex flex-row gap-0">
        {/* Vertical Tab List */}
        <TabsList className="flex flex-col h-auto w-10 gap-1 bg-transparent my-4 border-r rounded-none">
          {tabs.map((tab) => (
            <IconTabTrigger key={tab.value} value={tab.value} icon={tab.icon} label={tab.label} />
          ))}
        </TabsList>

        {/* Tab Contents */}
        <TabsContents className="flex-1 py-2">
          {tabs.map((tab) => (
            <TabsContent
              key={tab.value}
              value={tab.value}
              className={cn("mt-0 max-h-60 overflow-y-auto overflow-x-hidden")}
            >
              <AnimatePresence mode="wait">
                <motion.div
                  key={collapsed ? "collapsed" : "expanded"}
                  initial={{ opacity: 0 }}
                  animate={{ opacity: 1 }}
                  exit={{ opacity: 0 }}
                  transition={{ duration: 0.2 }}
                >
                  <BlockGrid blocks={tab.blocks} collapsed={collapsed} />
                </motion.div>
              </AnimatePresence>
            </TabsContent>
          ))}
        </TabsContents>
      </Tabs>
    </div>
  );
}
