import { describe, it, expect } from "vitest";
import { TextBlockController } from "../controllers/text-block";

describe("TextBlockController", () => {
  it("exposes expected Stimulus targets and values", () => {
    expect(TextBlockController.targets).toEqual(["editor"]);
    expect(TextBlockController.values).toEqual({
      blockId: String,
      content: String,
    });
  });
});
