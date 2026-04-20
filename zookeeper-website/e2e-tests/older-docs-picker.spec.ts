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

test.describe("Older Docs Picker – sidebar", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/docs");
    await page.waitForLoadState("networkidle");
  });

  test("trigger button is visible in the docs sidebar", async ({ page }) => {
    const trigger = page.getByRole("button", { name: /older docs/i });
    await expect(trigger).toBeVisible();
  });

  test("popover is closed by default", async ({ page }) => {
    await expect(page.getByRole("combobox")).not.toBeVisible();
  });

  test("clicking the trigger opens a popover with a search input", async ({
    page
  }) => {
    await page.getByRole("button", { name: /older docs/i }).click();
    await expect(page.getByRole("combobox")).toBeVisible();
  });

  test("popover lists released doc versions", async ({ page }) => {
    await page.getByRole("button", { name: /older docs/i }).click();

    const list = page.getByRole("listbox");
    await expect(list).toBeVisible();

    const options = list.getByRole("option");
    await expect(options.first()).toBeVisible();
    const count = await options.count();
    expect(count).toBeGreaterThan(0);
  });

  test("versions are displayed in descending order", async ({ page }) => {
    await page.getByRole("button", { name: /older docs/i }).click();

    const options = page.getByRole("option");
    await expect(options.first()).toBeVisible();
    const count = await options.count();
    expect(count).toBeGreaterThan(1);

    const firstText = (await options.first().textContent()) ?? "";
    const lastText = (await options.last().textContent()) ?? "";

    // Simple sanity check: the first item should sort higher than the last
    expect(firstText.trim()).not.toBe("");
    expect(lastText.trim()).not.toBe("");
    expect(firstText.trim() > lastText.trim()).toBe(true);
  });

  test("each version item is a link to /released-docs/r{version}/index.html", async ({
    page
  }) => {
    await page.getByRole("button", { name: /older docs/i }).click();

    const options = page.getByRole("option");
    await expect(options.first()).toBeVisible();

    // With asChild, the <a> IS the option element — check href directly
    const count = Math.min(await options.count(), 5);
    for (let i = 0; i < count; i++) {
      const href = await options.nth(i).getAttribute("href");
      expect(href).toMatch(/^\/released-docs\/r[\d.].+\/index\.html$/);
    }
  });

  test("typing in the search box filters the version list", async ({
    page
  }) => {
    await page.getByRole("button", { name: /older docs/i }).click();

    const input = page.getByRole("combobox");
    await expect(input).toBeVisible();

    const allOptions = page.getByRole("option");
    const totalBefore = await allOptions.count();

    // Type a prefix that matches only a subset of versions
    await input.fill("3.9");
    await page.waitForTimeout(200);

    const filtered = page.getByRole("option");
    const totalAfter = await filtered.count();

    // The list should be smaller after filtering
    expect(totalAfter).toBeLessThan(totalBefore);

    // Every remaining option must contain the search term
    for (let i = 0; i < totalAfter; i++) {
      const text = await filtered.nth(i).textContent();
      expect(text).toContain("3.9");
    }
  });

  test("searching for a non-existent version shows 'No versions found'", async ({
    page
  }) => {
    await page.getByRole("button", { name: /older docs/i }).click();

    const input = page.getByRole("combobox");
    await input.fill("99.99.99");
    await page.waitForTimeout(200);

    await expect(page.getByText(/no versions found/i)).toBeVisible();
  });

  test("search is cleared when the popover is reopened", async ({ page }) => {
    const trigger = page.getByRole("button", { name: /older docs/i });

    await trigger.click();
    const input = page.getByRole("combobox");
    await input.fill("3.9");

    // Close the popover by pressing Escape
    await page.keyboard.press("Escape");
    await expect(page.getByRole("combobox")).not.toBeVisible();

    // Reopen
    await trigger.click();
    const newInput = page.getByRole("combobox");
    await expect(newInput).toHaveValue("");
  });
});

test.describe("Older Docs Picker – navbar Documentation menu", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await page.waitForLoadState("networkidle");
  });

  test("Documentation menu contains an 'Older docs' sub-menu trigger", async ({
    page
  }) => {
    // Open the Documentation dropdown (scope to banner to avoid matching the mobile collapsible)
    await page
      .getByRole("banner")
      .getByRole("button", { name: /documentation/i })
      .click();

    // The sub-menu trigger should be visible
    const olderDocs = page.getByRole("menuitem", { name: /older docs/i });
    await expect(olderDocs).toBeVisible();
  });

  test("hovering 'Older docs' in the navbar opens a version sub-menu", async ({
    page
  }) => {
    await page
      .getByRole("banner")
      .getByRole("button", { name: /documentation/i })
      .click();

    const olderDocs = page.getByRole("menuitem", { name: /older docs/i });
    await olderDocs.hover();
    // ArrowRight reliably opens Radix sub-menus cross-browser (hover alone is flaky in Firefox)
    await olderDocs.press("ArrowRight");

    // Wait until the sub-menu actually opens (a second menu element becomes visible)
    await expect(page.getByRole("menu")).toHaveCount(2, { timeout: 10000 });
    const subMenu = page.getByRole("menu").last();

    const options = subMenu.getByRole("option");
    await expect(options.first()).toBeVisible();
  });

  test("navbar older-docs links point to /released-docs/", async ({ page }) => {
    await page
      .getByRole("banner")
      .getByRole("button", { name: /documentation/i })
      .click();

    const olderDocs = page.getByRole("menuitem", { name: /older docs/i });
    await olderDocs.hover();

    const subMenu = page.getByRole("menu").last();
    await expect(subMenu).toBeVisible();

    const links = subMenu.getByRole("link");
    const count = Math.min(await links.count(), 3);
    for (let i = 0; i < count; i++) {
      const href = await links.nth(i).getAttribute("href");
      expect(href).toMatch(/^\/released-docs\/r[\d.].+\/index\.html$/);
    }
  });
});
