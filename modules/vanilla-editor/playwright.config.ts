import { defineConfig, devices } from "@playwright/test";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  testDir: "./src/__e2e__",
  testIgnore: [
    "**/fixtures/**",
    "**/setup/**",
    "**/integration/**",
    "**/core/**",
    "**/shared/**",
    "**/smoke/**",
    "**/test-page.html",
    "**/*.old.ts",
  ],
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : 4,
  reporter: [
    ["html", { outputFolder: "playwright-report" }],
    ["json", { outputFile: "test-results/e2e-results.json" }],
  ],
  globalSetup: path.resolve(__dirname, "./src/__e2e__/global-setup.ts"),
  use: {
    baseURL: "http://localhost:4000",
    storageState: ".auth/storage-state.json",
    actionTimeout: 10000,
    navigationTimeout: 30000,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
      },
    },
  ],
});
