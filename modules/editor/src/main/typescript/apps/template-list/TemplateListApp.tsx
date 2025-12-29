import { useState, useCallback } from "react";
import type { TemplateSummary } from "../../template-list";
import { TemplateTable } from "./components/TemplateTable";
import { CreateTemplateForm } from "./components/CreateTemplateForm";
import { SearchBox } from "./components/SearchBox";

interface TemplateListAppProps {
  tenantId: number;
  initialTemplates: TemplateSummary[];
  baseUrl: string;
}

export function TemplateListApp({
  tenantId,
  initialTemplates,
  baseUrl,
}: TemplateListAppProps) {
  const [templates, setTemplates] = useState<TemplateSummary[]>(initialTemplates);
  const [searchTerm, setSearchTerm] = useState("");
  const [isLoading, setIsLoading] = useState(false);

  const fetchTemplates = useCallback(
    async (search?: string) => {
      setIsLoading(true);
      try {
        const url = new URL(
          `/v1/tenants/${tenantId}/templates`,
          window.location.origin,
        );
        if (search) url.searchParams.set("q", search);

        const response = await fetch(url.toString(), {
          headers: {
            Accept: "application/vnd.epistola.v1+json",
          },
        });

        if (response.ok) {
          const data = await response.json();
          // Map v1 API response to TemplateSummary format
          const items = (data.items ?? []).map(
            (item: {
              id: number;
              name: string;
              lastModified: string;
              variantCount?: number;
              hasDraft?: boolean;
              publishedVersionCount?: number;
            }) => ({
              id: item.id,
              name: item.name,
              lastModified: item.lastModified,
              variantCount: item.variantCount ?? 0,
              hasDraft: item.hasDraft ?? false,
              publishedVersionCount: item.publishedVersionCount ?? 0,
            }),
          );
          setTemplates(items);
        }
      } catch (error) {
        console.error("Failed to fetch templates:", error);
      } finally {
        setIsLoading(false);
      }
    },
    [tenantId],
  );

  const handleSearch = useCallback(
    (term: string) => {
      setSearchTerm(term);
      fetchTemplates(term);
    },
    [fetchTemplates],
  );

  const handleTemplateCreated = useCallback(() => {
    fetchTemplates(searchTerm);
  }, [fetchTemplates, searchTerm]);

  return (
    <div className="space-y-8">
      <CreateTemplateForm tenantId={tenantId} onCreated={handleTemplateCreated} />

      <section>
        <h2 className="text-lg font-semibold text-red-500 mb-4">Existing Templates</h2>
        <SearchBox value={searchTerm} onChange={handleSearch} />
        <TemplateTable
          templates={templates}
          baseUrl={baseUrl}
          isLoading={isLoading}
        />
      </section>
    </div>
  );
}
