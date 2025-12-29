import type { TemplateSummary } from "../../../template-list";
import { Button } from "@/components/ui/button";

interface TemplateTableProps {
  templates: TemplateSummary[];
  baseUrl: string;
  isLoading: boolean;
}

function formatDate(isoString: string): string {
  return new Date(isoString).toLocaleString("en-US", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function TemplateTable({ templates, baseUrl, isLoading }: TemplateTableProps) {
  return (
    <div className="relative rounded-md border">
      {isLoading && (
        <div className="absolute inset-0 bg-background/50 flex items-center justify-center z-10">
          <span className="text-muted-foreground">Loading...</span>
        </div>
      )}
      <table className="w-full">
        <thead>
          <tr className="border-b bg-muted/50">
            <th className="text-left p-3 font-medium">ID</th>
            <th className="text-left p-3 font-medium">Name</th>
            <th className="text-left p-3 font-medium">Variants</th>
            <th className="text-left p-3 font-medium">Draft</th>
            <th className="text-left p-3 font-medium">Published</th>
            <th className="text-left p-3 font-medium">Last Modified</th>
            <th className="text-left p-3 font-medium">Actions</th>
          </tr>
        </thead>
        <tbody>
          {templates.length === 0 ? (
            <tr>
              <td colSpan={7} className="text-center p-8 text-muted-foreground">
                No templates yet. Create one above.
              </td>
            </tr>
          ) : (
            templates.map((template) => (
              <tr key={template.id} className="border-b hover:bg-muted/50">
                <td className="p-3">{template.id}</td>
                <td className="p-3">{template.name}</td>
                <td className="p-3">{template.variantCount}</td>
                <td className="p-3">
                  {template.hasDraft ? (
                    <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-yellow-100 text-yellow-800">
                      Draft
                    </span>
                  ) : (
                    <span className="text-muted-foreground">—</span>
                  )}
                </td>
                <td className="p-3">
                  {template.publishedVersionCount > 0 ? (
                    <span>v{template.publishedVersionCount}</span>
                  ) : (
                    <span className="text-muted-foreground">—</span>
                  )}
                </td>
                <td className="p-3">{formatDate(template.lastModified)}</td>
                <td className="p-3">
                  <Button variant="outline" size="sm" asChild>
                    <a href={`${baseUrl}/${template.id}`}>View</a>
                  </Button>
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
