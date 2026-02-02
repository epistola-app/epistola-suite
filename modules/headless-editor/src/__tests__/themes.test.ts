import { describe, it, expect, vi } from "vitest";
import { TemplateEditor } from "../editor";
import type { ThemeSummary } from "../types";

describe("TemplateEditor themes", () => {
  describe("setThemes", () => {
    it("should set available themes", () => {
      const editor = new TemplateEditor();
      const themes: ThemeSummary[] = [
        { id: "theme-1", name: "Theme One", description: "First theme" },
        { id: "theme-2", name: "Theme Two" },
      ];

      editor.setThemes(themes);

      expect(editor.getThemes()).toEqual(themes);
    });

    it("should replace existing themes", () => {
      const editor = new TemplateEditor();
      const initialThemes: ThemeSummary[] = [{ id: "old", name: "Old Theme" }];
      const newThemes: ThemeSummary[] = [{ id: "new", name: "New Theme" }];

      editor.setThemes(initialThemes);
      editor.setThemes(newThemes);

      expect(editor.getThemes()).toEqual(newThemes);
      expect(editor.getThemes()).toHaveLength(1);
    });

    it("should allow setting empty themes array", () => {
      const editor = new TemplateEditor();
      const themes: ThemeSummary[] = [{ id: "theme-1", name: "Theme One" }];

      editor.setThemes(themes);
      expect(editor.getThemes()).toHaveLength(1);

      editor.setThemes([]);
      expect(editor.getThemes()).toHaveLength(0);
    });
  });

  describe("setDefaultTheme", () => {
    it("should set default theme", () => {
      const editor = new TemplateEditor();
      const theme: ThemeSummary = {
        id: "default",
        name: "Default Theme",
        description: "The default",
      };

      editor.setDefaultTheme(theme);

      expect(editor.getDefaultTheme()).toEqual(theme);
    });

    it("should allow clearing default theme with null", () => {
      const editor = new TemplateEditor();
      const theme: ThemeSummary = { id: "default", name: "Default Theme" };

      editor.setDefaultTheme(theme);
      expect(editor.getDefaultTheme()).not.toBeNull();

      editor.setDefaultTheme(null);
      expect(editor.getDefaultTheme()).toBeNull();
    });

    it("should have null default theme initially", () => {
      const editor = new TemplateEditor();

      expect(editor.getDefaultTheme()).toBeNull();
    });
  });

  describe("updateThemeId", () => {
    it("should update template themeId", () => {
      const editor = new TemplateEditor();

      editor.updateThemeId("theme-123");

      expect(editor.getTemplate().themeId).toBe("theme-123");
    });

    it("should allow clearing themeId with null", () => {
      const editor = new TemplateEditor();

      editor.updateThemeId("theme-123");
      expect(editor.getTemplate().themeId).toBe("theme-123");

      editor.updateThemeId(null);
      expect(editor.getTemplate().themeId).toBeNull();
    });

    it("should trigger template change callback", () => {
      const onTemplateChange = vi.fn();
      const editor = new TemplateEditor({
        callbacks: { onTemplateChange },
      });

      editor.updateThemeId("theme-123");

      expect(onTemplateChange).toHaveBeenCalled();
      const lastCall =
        onTemplateChange.mock.calls[onTemplateChange.mock.calls.length - 1];
      expect(lastCall![0].themeId).toBe("theme-123");
    });
  });

  describe("undo reverts theme change", () => {
    it("should revert themeId on undo", () => {
      const editor = new TemplateEditor();

      editor.updateThemeId("theme-1");
      expect(editor.getTemplate().themeId).toBe("theme-1");

      editor.updateThemeId("theme-2");
      expect(editor.getTemplate().themeId).toBe("theme-2");

      editor.undo();
      expect(editor.getTemplate().themeId).toBe("theme-1");
    });

    it("should revert to null on undo when themeId was initially null", () => {
      const editor = new TemplateEditor();

      expect(editor.getTemplate().themeId).toBeUndefined();

      editor.updateThemeId("theme-1");
      expect(editor.getTemplate().themeId).toBe("theme-1");

      editor.undo();
      expect(editor.getTemplate().themeId).toBeUndefined();
    });

    it("should support multiple undos for theme changes", () => {
      const editor = new TemplateEditor();

      editor.updateThemeId("theme-a");
      editor.updateThemeId("theme-b");
      editor.updateThemeId("theme-c");

      expect(editor.getTemplate().themeId).toBe("theme-c");

      editor.undo();
      expect(editor.getTemplate().themeId).toBe("theme-b");

      editor.undo();
      expect(editor.getTemplate().themeId).toBe("theme-a");

      editor.undo();
      expect(editor.getTemplate().themeId).toBeUndefined();
    });

    it("should support redo after undo for theme changes", () => {
      const editor = new TemplateEditor();

      editor.updateThemeId("theme-1");
      editor.updateThemeId("theme-2");

      editor.undo();
      expect(editor.getTemplate().themeId).toBe("theme-1");

      editor.redo();
      expect(editor.getTemplate().themeId).toBe("theme-2");
    });
  });

  describe("theme atoms in getStores", () => {
    it("should export $themes atom", () => {
      const editor = new TemplateEditor();
      const stores = editor.getStores();

      expect(stores.$themes).toBeDefined();
    });

    it("should export $defaultTheme atom", () => {
      const editor = new TemplateEditor();
      const stores = editor.getStores();

      expect(stores.$defaultTheme).toBeDefined();
    });

    it("should allow subscribing to $themes via getStores", () => {
      const editor = new TemplateEditor();
      const stores = editor.getStores();
      const themes: ThemeSummary[] = [{ id: "t1", name: "Theme 1" }];

      let notified = false;
      stores.$themes.subscribe((value) => {
        if (value.length > 0) notified = true;
      });

      editor.setThemes(themes);
      expect(notified).toBe(true);
    });

    it("should allow subscribing to $defaultTheme via getStores", () => {
      const editor = new TemplateEditor();
      const stores = editor.getStores();
      const theme: ThemeSummary = { id: "default", name: "Default" };

      let notifiedValue: ThemeSummary | null = null;
      stores.$defaultTheme.subscribe((value) => {
        notifiedValue = value;
      });

      editor.setDefaultTheme(theme);
      expect(notifiedValue).toEqual(theme);
    });
  });
});
