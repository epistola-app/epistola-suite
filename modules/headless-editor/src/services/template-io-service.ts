import type {
  EditorCallbacks,
  Template,
  ValidationResult,
} from "../types.js";
import { EditorStateRepository } from "./editor-state-repository.js";

type ValidateTemplate = (template: Template) => ValidationResult;
type GetCallbacks = () => EditorCallbacks;

export class TemplateIoService {
  constructor(
    private readonly stateRepository: EditorStateRepository,
    private readonly validateTemplate: ValidateTemplate,
    private readonly getCallbacks: GetCallbacks,
  ) {}

  getTemplate(): Template {
    return this.stateRepository.getTemplate();
  }

  setTemplate(template: Template): void {
    this.stateRepository.setTemplate(template);
  }

  updateTemplate(updates: Partial<Omit<Template, "blocks">>): void {
    const current = this.stateRepository.getTemplate();
    this.stateRepository.setTemplate({ ...current, ...updates });
  }

  validateCurrentTemplate(): ValidationResult {
    return this.validateTemplate(this.stateRepository.getTemplate());
  }

  exportJSON(): string {
    return JSON.stringify(this.stateRepository.getTemplate(), null, 2);
  }

  importJSON(json: string): void {
    try {
      const parsed: unknown = JSON.parse(json);

      if (typeof parsed !== "object" || parsed === null) {
        throw new Error("Template must be an object");
      }

      const obj = parsed as Record<string, unknown>;

      if (typeof obj.id !== "string" || obj.id.length === 0) {
        throw new Error("Template must have a non-empty string id");
      }

      if (typeof obj.name !== "string") {
        throw new Error("Template must have a string name");
      }

      if (!Array.isArray(obj.blocks)) {
        throw new Error("Template must have a blocks array");
      }

      this.stateRepository.setTemplate(parsed as Template);
    } catch (error) {
      this.getCallbacks().onError?.(
        new Error(`Invalid JSON: ${(error as Error).message}`),
      );
    }
  }
}
