import { useState, useCallback, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

interface CreateTemplateFormProps {
  tenantId: number;
  onCreated: () => void;
}

interface FormErrors {
  name?: string;
}

export function CreateTemplateForm({ tenantId, onCreated }: CreateTemplateFormProps) {
  const [name, setName] = useState("");
  const [errors, setErrors] = useState<FormErrors>({});
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleSubmit = useCallback(
    async (e: FormEvent) => {
      e.preventDefault();
      setIsSubmitting(true);
      setErrors({});

      try {
        const response = await fetch(`/v1/tenants/${tenantId}/templates`, {
          method: "POST",
          headers: {
            Accept: "application/vnd.epistola.v1+json",
            "Content-Type": "application/vnd.epistola.v1+json",
          },
          body: JSON.stringify({ name }),
        });

        if (response.ok) {
          setName("");
          onCreated();
        } else {
          try {
            const data = await response.json();
            if (data.message) {
              setErrors({ name: data.message });
            }
          } catch {
            setErrors({ name: "Failed to create template" });
          }
        }
      } catch (error) {
        console.error("Failed to create template:", error);
        setErrors({ name: "Failed to create template" });
      } finally {
        setIsSubmitting(false);
      }
    },
    [name, tenantId, onCreated],
  );

  return (
    <section className="p-6 border rounded-lg bg-card">
      <h2 className="text-lg font-semibold mb-4">Create New Template</h2>
      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="name">Name</Label>
          <Input
            id="name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Template name"
            required
            aria-invalid={!!errors.name}
            className={errors.name ? "border-destructive" : ""}
          />
          {errors.name && <p className="text-sm text-destructive">{errors.name}</p>}
        </div>
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting ? "Creating..." : "Create Template"}
        </Button>
      </form>
    </section>
  );
}
