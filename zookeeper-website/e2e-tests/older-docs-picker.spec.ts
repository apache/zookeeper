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
import { DOCS_ROOT } from "./constants";
import {
  getReleasedDocUrl,
  getReleasedDocVersions
} from "../app/lib/released-docs-versions";

const EXPECTED_VERSIONS = getReleasedDocVersions();

test.describe("Older Docs Picker – sidebar", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(DOCS_ROOT);
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
    await expect(options).toHaveCount(EXPECTED_VERSIONS.length);
  });

  test("versions are displayed in descending order", async ({ page }) => {
    await page.getByRole("button", { name: /older docs/i }).click();

    const options = page.getByRole("option");
    await expect(options.first()).toBeVisible();

    const texts = await options.allTextContents();
    expect(texts.map((text) => text.trim())).toEqual(EXPECTED_VERSIONS);
  });

  test("each version item links to the correct archive path", async ({
    page
  }) => {
    await page.getByRole("button", { name: /older docs/i }).click();

    const options = page.getByRole("option");
    await expect(options).toHaveCount(EXPECTED_VERSIONS.length);
    for (let i = 0; i < EXPECTED_VERSIONS.length; i++) {
      const href = await options.nth(i).getAttribute("href");
      expect(href).toBe(getReleasedDocUrl(EXPECTED_VERSIONS[i]));
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

    const [major, minor] = EXPECTED_VERSIONS[0].split(".");
    const prefix = `${major}.${minor}`;
    const expectedMatches = EXPECTED_VERSIONS.filter((v) =>
      v.startsWith(prefix)
    ).length;

    await input.fill(prefix);
    await page.waitForTimeout(200);

    const filtered = page.getByRole("option");
    const totalAfter = await filtered.count();

    expect(totalBefore).toBe(EXPECTED_VERSIONS.length);
    expect(totalAfter).toBe(expectedMatches);

    // Every remaining option must contain the search term
    for (let i = 0; i < totalAfter; i++) {
      const text = await filtered.nth(i).textContent();
      expect(text).toContain(prefix);
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
    await input.fill("anything");

    // Close the popover by pressing Escape
    await page.keyboard.press("Escape");
    await expect(page.getByRole("combobox")).not.toBeVisible();

    // Reopen
    await trigger.click();
    const newInput = page.getByRole("combobox");
    await expect(newInput).toHaveValue("");
  });
});
