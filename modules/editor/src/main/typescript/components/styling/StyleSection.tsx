import { useState } from "react";

interface StyleSectionProps {
  title: string;
  defaultExpanded?: boolean;
  children: React.ReactNode;
}

export function StyleSection({ title, defaultExpanded = false, children }: StyleSectionProps) {
  const [expanded, setExpanded] = useState(defaultExpanded);

  return (
    <div className="border-b border-gray-200">
      <button
        type="button"
        onClick={() => setExpanded(!expanded)}
        className="w-full px-3 py-2 flex items-center justify-between
                   text-sm font-medium text-gray-700 hover:bg-gray-50 transition-colors"
      >
        <span>{title}</span>
        <span className={`transform transition-transform ${expanded ? "rotate-180" : ""}`}>
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
          </svg>
        </span>
      </button>
      {expanded && <div className="px-3 pb-3 space-y-3">{children}</div>}
    </div>
  );
}
