import type {
  DataExample,
  JsonObject,
  JsonSchema,
  PreviewOverrides,
} from "../types.js";
import { DEFAULT_TEST_DATA, DEFAULT_PREVIEW_OVERRIDES } from "../types.js";
import { EditorStateRepository } from "./editor-state-repository.js";

export class EditorStateService {
  constructor(private readonly stateRepository: EditorStateRepository) {}

  setSchema(schema: JsonSchema | null): void {
    this.stateRepository.setSchema(schema);
  }

  getSchema(): JsonSchema | null {
    return this.stateRepository.getSchema();
  }

  setDataExamples(examples: DataExample[]): void {
    const currentSelectedId = this.stateRepository.getSelectedDataExampleId();

    this.stateRepository.setDataExamples(examples);

    if (examples.length > 0 && !currentSelectedId) {
      this.selectDataExample(examples[0]!.id);
    } else if (examples.length === 0) {
      this.stateRepository.setSelectedDataExampleId(null);
      this.stateRepository.setTestData(
        JSON.parse(JSON.stringify(DEFAULT_TEST_DATA)) as JsonObject,
      );
    }
  }

  addDataExample(example: DataExample): void {
    const current = this.stateRepository.getDataExamples();
    this.stateRepository.setDataExamples([...current, example]);
  }

  updateDataExample(id: string, updates: Partial<DataExample>): void {
    const current = this.stateRepository.getDataExamples();
    const updatedExamples = current.map((example) => {
      if (example.id === id) {
        return { ...example, ...updates };
      }
      return example;
    });

    this.stateRepository.setDataExamples(updatedExamples);

    if (this.stateRepository.getSelectedDataExampleId() === id && updates.data) {
      this.stateRepository.setTestData(
        JSON.parse(JSON.stringify(updates.data)) as JsonObject,
      );
    }
  }

  deleteDataExample(id: string): void {
    const current = this.stateRepository.getDataExamples();
    const wasSelected = this.stateRepository.getSelectedDataExampleId() === id;

    const filtered = current.filter((example) => example.id !== id);
    this.stateRepository.setDataExamples(filtered);

    if (wasSelected) {
      if (filtered.length > 0) {
        this.selectDataExample(filtered[0]!.id);
      } else {
        this.stateRepository.setSelectedDataExampleId(null);
        this.stateRepository.setTestData(
          JSON.parse(JSON.stringify(DEFAULT_TEST_DATA)) as JsonObject,
        );
      }
    }
  }

  selectDataExample(id: string | null): void {
    this.stateRepository.setSelectedDataExampleId(id);

    if (id === null) {
      this.stateRepository.setTestData(
        JSON.parse(JSON.stringify(DEFAULT_TEST_DATA)) as JsonObject,
      );
      return;
    }

    const examples = this.stateRepository.getDataExamples();
    const selected = examples.find((example) => example.id === id);
    if (selected) {
      this.stateRepository.setTestData(
        JSON.parse(JSON.stringify(selected.data)) as JsonObject,
      );
    }
  }

  getDataExamples(): DataExample[] {
    return this.stateRepository.getDataExamples();
  }

  getSelectedDataExampleId(): string | null {
    return this.stateRepository.getSelectedDataExampleId();
  }

  getTestData(): JsonObject {
    return this.stateRepository.getTestData();
  }

  setPreviewOverride(
    type: "conditionals" | "loops",
    blockId: string,
    value: "data" | "show" | "hide" | number,
  ): void {
    const current = this.stateRepository.getPreviewOverrides();
    const updated: PreviewOverrides = {
      conditionals: { ...current.conditionals },
      loops: { ...current.loops },
    };

    if (type === "conditionals") {
      updated.conditionals[blockId] = value as "data" | "show" | "hide";
    } else {
      updated.loops[blockId] = value as number | "data";
    }

    this.stateRepository.setPreviewOverrides(updated);
  }

  getPreviewOverrides(): PreviewOverrides {
    return this.stateRepository.getPreviewOverrides();
  }

  clearPreviewOverrides(): void {
    this.stateRepository.setPreviewOverrides({ ...DEFAULT_PREVIEW_OVERRIDES });
  }

  selectBlock(id: string | null): void {
    this.stateRepository.setSelectedBlockId(id);
  }

  getSelectedBlockId(): string | null {
    return this.stateRepository.getSelectedBlockId();
  }
}
