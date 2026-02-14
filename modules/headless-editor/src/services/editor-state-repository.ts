import { computed } from "nanostores";
import { createEditorStore, type EditorStore } from "../store.js";
import type {
  EditorState,
  JsonSchema,
  Template,
  DataExample,
  JsonObject,
  PreviewOverrides,
  ThemeSummary,
} from "../types.js";

export class EditorStateRepository {
  private readonly store: EditorStore;

  readonly $isDirty;

  constructor(initialTemplate: Template) {
    this.store = createEditorStore(initialTemplate);

    this.$isDirty = computed(
      [this.store.$template, this.store.$lastSavedTemplate],
      (template, lastSaved) => {
        if (lastSaved === null) {
          return template.blocks.length > 0 || template.name !== "Untitled";
        }
        return JSON.stringify(template) !== JSON.stringify(lastSaved);
      },
    );
  }

  getStateSnapshot(): EditorState {
    return {
      template: this.store.getTemplate(),
      selectedBlockId: this.store.getSelectedBlockId(),
      dataExamples: this.store.getDataExamples(),
      selectedDataExampleId: this.store.getSelectedDataExampleId(),
      testData: this.store.getTestData(),
    };
  }

  subscribeState(callback: (state: EditorState) => void): () => void {
    const unsubTemplate = this.store.subscribeTemplate(() => {
      callback(this.getStateSnapshot());
    });
    const unsubSelected = this.store.subscribeSelectedBlockId(() => {
      callback(this.getStateSnapshot());
    });
    const unsubDataExamples = this.store.subscribeDataExamples(() => {
      callback(this.getStateSnapshot());
    });
    const unsubSelectedDataExampleId =
      this.store.subscribeSelectedDataExampleId(() => {
        callback(this.getStateSnapshot());
      });
    const unsubTestData = this.store.subscribeTestData(() => {
      callback(this.getStateSnapshot());
    });

    return () => {
      unsubTemplate();
      unsubSelected();
      unsubDataExamples();
      unsubSelectedDataExampleId();
      unsubTestData();
    };
  }

  getStoreRefs() {
    return {
      $template: this.store.$template,
      $selectedBlockId: this.store.$selectedBlockId,
      $dataExamples: this.store.$dataExamples,
      $selectedDataExampleId: this.store.$selectedDataExampleId,
      $testData: this.store.$testData,
      $schema: this.store.$schema,
      $lastSavedTemplate: this.store.$lastSavedTemplate,
      $previewOverrides: this.store.$previewOverrides,
      $themes: this.store.$themes,
      $defaultTheme: this.store.$defaultTheme,
    };
  }

  markAsSaved(): void {
    const currentTemplate = this.store.getTemplate();
    this.store.setLastSavedTemplate(
      JSON.parse(JSON.stringify(currentTemplate)) as Template,
    );
  }

  isDirty(): boolean {
    const currentTemplate = this.store.getTemplate();
    const lastSaved = this.store.getLastSavedTemplate();
    if (lastSaved === null) {
      return (
        currentTemplate.blocks.length > 0 || currentTemplate.name !== "Untitled"
      );
    }

    return JSON.stringify(currentTemplate) !== JSON.stringify(lastSaved);
  }

  subscribeTemplate(callback: (template: Template) => void): () => void {
    return this.store.subscribeTemplate(callback);
  }

  subscribeSelectedBlockId(callback: (id: string | null) => void): () => void {
    return this.store.subscribeSelectedBlockId(callback);
  }

  getTemplate(): Template {
    return this.store.getTemplate();
  }

  setTemplate(template: Template): void {
    this.store.setTemplate(template);
  }

  getSelectedBlockId(): string | null {
    return this.store.getSelectedBlockId();
  }

  setSelectedBlockId(id: string | null): void {
    this.store.setSelectedBlockId(id);
  }

  getDataExamples(): DataExample[] {
    return this.store.getDataExamples();
  }

  setDataExamples(examples: DataExample[]): void {
    this.store.setDataExamples(examples);
  }

  getSelectedDataExampleId(): string | null {
    return this.store.getSelectedDataExampleId();
  }

  setSelectedDataExampleId(id: string | null): void {
    this.store.setSelectedDataExampleId(id);
  }

  getTestData(): JsonObject {
    return this.store.getTestData();
  }

  setTestData(data: JsonObject): void {
    this.store.setTestData(data);
  }

  getSchema(): JsonSchema | null {
    return this.store.getSchema();
  }

  setSchema(schema: JsonSchema | null): void {
    this.store.setSchema(schema);
  }

  getLastSavedTemplate(): Template | null {
    return this.store.getLastSavedTemplate();
  }

  setLastSavedTemplate(template: Template | null): void {
    this.store.setLastSavedTemplate(template);
  }

  getPreviewOverrides(): PreviewOverrides {
    return this.store.getPreviewOverrides();
  }

  setPreviewOverrides(overrides: PreviewOverrides): void {
    this.store.setPreviewOverrides(overrides);
  }

  getThemes(): ThemeSummary[] {
    return this.store.getThemes();
  }

  setThemes(themes: ThemeSummary[]): void {
    this.store.setThemes(themes);
  }

  getDefaultTheme(): ThemeSummary | null {
    return this.store.getDefaultTheme();
  }

  setDefaultTheme(theme: ThemeSummary | null): void {
    this.store.setDefaultTheme(theme);
  }
}
