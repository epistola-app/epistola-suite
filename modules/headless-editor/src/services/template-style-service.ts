import { BlockTree } from "../store.js";
import {
  resolveBlockStylesWithAncestors,
  resolveDocumentStyles,
} from "../styles/cascade.js";
import type {
  Block,
  CSSStyles,
  DocumentStyles,
  PageSettings,
  ThemeSummary,
} from "../types.js";
import { EditorStateRepository } from "./editor-state-repository.js";

type RecordMutation = () => void;

export class TemplateStyleService {
  constructor(
    private readonly stateRepository: EditorStateRepository,
    private readonly recordMutation: RecordMutation,
  ) {}

  setThemes(themes: ThemeSummary[]): void {
    this.stateRepository.setThemes(themes);
  }

  setDefaultTheme(theme: ThemeSummary | null): void {
    this.stateRepository.setDefaultTheme(theme);
  }

  getThemes(): ThemeSummary[] {
    return this.stateRepository.getThemes();
  }

  getDefaultTheme(): ThemeSummary | null {
    return this.stateRepository.getDefaultTheme();
  }

  updateThemeId(themeId: string | null): void {
    this.recordMutation();

    const current = this.stateRepository.getTemplate();
    this.stateRepository.setTemplate({
      ...current,
      themeId,
    });
  }

  updatePageSettings(settings: Partial<PageSettings>): void {
    this.recordMutation();

    const current = this.stateRepository.getTemplate();
    const currentPageSettings = current.pageSettings ?? {
      format: "A4",
      orientation: "portrait",
      margins: { top: 20, right: 20, bottom: 20, left: 20 },
    };

    const mergedMargins = settings.margins
      ? { ...currentPageSettings.margins, ...settings.margins }
      : currentPageSettings.margins;

    const updatedPageSettings: PageSettings = {
      ...currentPageSettings,
      ...settings,
      margins: mergedMargins,
    };

    this.stateRepository.setTemplate({
      ...current,
      pageSettings: updatedPageSettings,
    });
  }

  updateDocumentStyles(styles: Partial<DocumentStyles>): void {
    this.recordMutation();

    const current = this.stateRepository.getTemplate();
    const currentDocumentStyles = current.documentStyles ?? {};

    const updatedDocumentStyles: DocumentStyles = {
      ...currentDocumentStyles,
      ...styles,
    };

    this.stateRepository.setTemplate({
      ...current,
      documentStyles: updatedDocumentStyles,
    });
  }

  getResolvedDocumentStyles(): CSSStyles {
    const current = this.stateRepository.getTemplate();
    return resolveDocumentStyles(current.documentStyles);
  }

  getResolvedBlockStyles(blockId: string): CSSStyles {
    const current = this.stateRepository.getTemplate();
    const block = BlockTree.findBlock(current.blocks, blockId);
    if (!block) return {};

    const ancestors: Block[] = [];
    let currentParent = BlockTree.findParent(current.blocks, blockId);
    while (currentParent) {
      ancestors.unshift(currentParent);
      currentParent = BlockTree.findParent(current.blocks, currentParent.id);
    }

    const ancestorStyles = ancestors
      .map((ancestor) => ancestor.styles)
      .filter((styles): styles is CSSStyles => Boolean(styles));

    return resolveBlockStylesWithAncestors(
      current.documentStyles,
      ancestorStyles,
      block.styles,
    );
  }
}
