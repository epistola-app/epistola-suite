import { describe, expect, it } from "vitest";
import { buildShortcutGroupsProjection } from "./shortcuts.js";
import {
  SHORTCUT_HELPER_ONE_COLUMN_MAX_ITEMS,
  SHORTCUT_HELPER_TWO_COLUMN_MIN_ITEMS,
} from "../shortcuts/helper-projection.js";

describe("shortcut helper projection", () => {
  it("orders groups by helper display contract", () => {
    const projection = buildShortcutGroupsProjection();

    expect(projection.groups.map((group) => group.title)).toEqual([
      "Leader Key",
      "Leader Commands",
      "Core",
      "Text",
      "Insert",
      "Resize",
    ]);
  });

  it("keeps one-off leader key group full width", () => {
    const projection = buildShortcutGroupsProjection();
    const leaderKey = projection.groups[0];

    expect(leaderKey?.title).toBe("Leader Key");
    expect(leaderKey?.fullWidth).toBe(true);
    expect(leaderKey?.items.length).toBe(1);
  });

  it("applies deterministic layout thresholds per group item count", () => {
    const projection = buildShortcutGroupsProjection();

    for (const group of projection.groups) {
      if (group.items.length <= SHORTCUT_HELPER_ONE_COLUMN_MAX_ITEMS) {
        expect(group.layout).toBe("one-column");
      }
      if (group.items.length >= SHORTCUT_HELPER_TWO_COLUMN_MIN_ITEMS) {
        expect(group.layout).toBe("two-column");
      }
    }
  });

  it("supports search by command label and key text", () => {
    const byLabel = buildShortcutGroupsProjection({ query: "duplicate selected block" });
    expect(byLabel.groups).toHaveLength(1);
    expect(byLabel.groups[0]?.title).toBe("Leader Commands");

    const byKey = buildShortcutGroupsProjection({ query: "ctrl/cmd + z" });
    const core = byKey.groups.find((group) => group.title === "Core");
    expect(core).toBeDefined();
    expect(core?.items.some((item) => item.keys === "{cmd} + Z")).toBe(true);
  });

  it("marks active shortcut rows when active strokes are provided", () => {
    const projection = buildShortcutGroupsProjection({ activeStrokes: ["mod+space"] });

    const leaderKey = projection.groups.find((group) => group.title === "Leader Key");
    expect(leaderKey?.items.some((item) => item.active)).toBe(true);

    const leaderCommands = projection.groups.find((group) => group.title === "Leader Commands");
    expect(leaderCommands?.items.some((item) => item.active)).toBe(true);
  });

  it("snapshots layout metadata for section ordering and width rules", () => {
    const projection = buildShortcutGroupsProjection();

    expect(
      projection.groups.map((group) => ({
        id: group.id,
        title: group.title,
        fullWidth: group.fullWidth,
        layout: group.layout,
        itemCount: group.items.length,
      })),
    ).toMatchInlineSnapshot(`
      [
        {
          "fullWidth": true,
          "id": "leader-key",
          "itemCount": 1,
          "layout": "one-column",
          "title": "Leader Key",
        },
        {
          "fullWidth": false,
          "id": "leader-commands",
          "itemCount": 11,
          "layout": "two-column",
          "title": "Leader Commands",
        },
        {
          "fullWidth": false,
          "id": "core",
          "itemCount": 5,
          "layout": "one-column",
          "title": "Core",
        },
        {
          "fullWidth": false,
          "id": "text",
          "itemCount": 5,
          "layout": "one-column",
          "title": "Text",
        },
        {
          "fullWidth": false,
          "id": "insert",
          "itemCount": 18,
          "layout": "two-column",
          "title": "Insert",
        },
        {
          "fullWidth": false,
          "id": "resize",
          "itemCount": 3,
          "layout": "one-column",
          "title": "Resize",
        },
      ]
    `);
  });
});
