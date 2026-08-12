//
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

import { test, expect } from "@playwright/test";

test.describe("Theme Toggle Functionality", () => {
  test("theme toggle button exists on landing page", async ({ page }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("load");

    // Look for theme toggle button
    const themeButton = page
      .getByRole("button", { name: /Toggle theme/i })
      .first();
    await expect(themeButton).toBeVisible();
  });

  test("can toggle theme on landing page", async ({ page }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("load");

    // Get initial theme class
    const initialClass = await page.evaluate(() => {
      return document.documentElement.getAttribute("class") || "";
    });

    // Find and click the theme toggle button
    const themeButton = page
      .getByRole("button", { name: /Toggle theme/i })
      .first();
    await themeButton.click();

    // Wait for theme change
    await page.waitForTimeout(300);

    // Get new theme class
    const newClass = await page.evaluate(() => {
      return document.documentElement.getAttribute("class") || "";
    });

    // Classes should be different after toggle
    expect(newClass).not.toBe(initialClass);
  });

  test("theme persists across page navigation", async ({ page }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("load");

    // Toggle theme
    const themeButton = page
      .getByRole("button", { name: /Toggle theme/i })
      .first();
    await themeButton.click();
    await page.waitForTimeout(300);

    // Get theme after toggle
    const themeAfterToggle = await page.evaluate(() => {
      return document.documentElement.getAttribute("class") || "";
    });

    // Navigate to another page
    const teamLink = page.getByRole("link", { name: /Team/i }).first();
    if ((await teamLink.count()) > 0) {
      await teamLink.click();
      await page.waitForLoadState("load");

      // Check theme persisted
      const themeOnNewPage = await page.evaluate(() => {
        return document.documentElement.getAttribute("class") || "";
      });

      // Theme should be similar (may have additional classes but core theme should persist)
      expect(themeOnNewPage).toContain(
        themeAfterToggle.includes("dark") ? "dark" : "light"
      );
    }
  });

  test("theme toggle works multiple times", async ({ page }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("load");

    const themeButton = page
      .getByRole("button", { name: /Toggle theme/i })
      .first();

    // Get initial state
    const initialClass = await page.evaluate(() => {
      return document.documentElement.getAttribute("class") || "";
    });

    // Toggle once
    await themeButton.click();
    await page.waitForTimeout(300);
    const afterFirstToggle = await page.evaluate(() => {
      return document.documentElement.getAttribute("class") || "";
    });
    expect(afterFirstToggle).not.toBe(initialClass);

    // Toggle back
    await themeButton.click();
    await page.waitForTimeout(300);
    const afterSecondToggle = await page.evaluate(() => {
      return document.documentElement.getAttribute("class") || "";
    });
    expect(afterSecondToggle).not.toBe(afterFirstToggle);
  });
});
