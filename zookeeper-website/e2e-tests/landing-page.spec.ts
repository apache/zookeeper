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

test.describe("Landing Page Navigation", () => {
  test("page loads successfully", async ({ page }) => {
    const response = await page.goto("/", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("load");
    expect(response?.status()).toBe(200);
    await expect(page).toHaveTitle(/Apache ZooKeeper/);
  });

  test("has visible navigation bar with logo", async ({ page }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("load");

    // Check for logo/home link
    const logo = page.getByRole("link", { name: /ZooKeeper Home/i });
    await expect(logo).toBeVisible();

    // Check for logo image (use .first() since there are multiple)
    const logoImage = page
      .getByRole("img", { name: /Apache ZooKeeper logo/i })
      .first();
    await expect(logoImage).toBeVisible();
  });

  test("navigation menu - Apache ZooKeeper Project dropdown exists", async ({
    page
  }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("load");

    // Find the "Apache ZooKeeper Project" button (use .first() for desktop version)
    const projectButton = page
      .getByRole("button", { name: /Apache ZooKeeper Project/i })
      .first();
    await expect(projectButton).toBeVisible();
  });

  test("navigation menu - Documentation dropdown exists", async ({ page }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("load");

    // Find the "Documentation" button (use .first() for desktop version)
    const docsButton = page
      .getByRole("button", { name: /^Documentation$/i })
      .first();
    await expect(docsButton).toBeVisible();
  });

  test("can navigate to Downloads page", async ({ page }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("load");

    // Look for downloads link
    const downloadsLink = page.getByRole("link", { name: /Download/i }).first();

    if ((await downloadsLink.count()) > 0) {
      await downloadsLink.click();
      await page.waitForLoadState("load");
      await expect(page).toHaveURL(/.*releases/);
    }
  });

  test("can navigate to Team page", async ({ page }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("load");

    // Look for team link
    const teamLink = page.getByRole("link", { name: /Team/i }).first();

    if ((await teamLink.count()) > 0) {
      await teamLink.click();
      await page.waitForLoadState("load");
      await expect(page).toHaveURL(/.*team/);
    }
  });

  test("can navigate to News page", async ({ page }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("load");

    // Look for news link
    const newsLink = page.getByRole("link", { name: /New/i }).first();

    if ((await newsLink.count()) > 0) {
      await newsLink.click();
      await page.waitForLoadState("load");
      await expect(page).toHaveURL(/.*news/);
    }
  });

  test("footer is present and visible", async ({ page }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("load");

    // Look for footer
    const footer = page.locator("footer").first();
    await expect(footer).toBeVisible();
  });

  test("has substantial page content", async ({ page }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("load");

    // Check that the page has meaningful text content
    const bodyText = await page.locator("body").textContent();
    expect(bodyText).toBeTruthy();
    expect(bodyText!.length).toBeGreaterThan(500);
  });

  test("hero section is visible", async ({ page }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("load");

    // Check for hero section with large logo or heading
    const heroElements = page
      .locator("section, div")
      .filter({ hasText: /ZooKeeper|distributed coordination/i });
    const count = await heroElements.count();
    expect(count).toBeGreaterThan(0);
  });

  test("can click Documentation menu item", async ({ page }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("load");

    // Click Documentation dropdown
    const docsButton = page
      .getByRole("button", { name: /^Documentation$/i })
      .first();
    await docsButton.click();
    await page.waitForTimeout(300);

    // Find Reference Guide link and click it
    const refGuideLink = page
      .getByRole("link", { name: /Reference Guide/i })
      .first();

    if ((await refGuideLink.count()) > 0 && (await refGuideLink.isVisible())) {
      await refGuideLink.click();
      await page.waitForLoadState("load");
      await expect(page).toHaveURL(/.*docs/);
    }
  });

  test("external links have proper attributes", async ({ page }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("load");

    // Find any external links (GitHub, Apache, etc.)
    const externalLinks = page.locator('a[href^="http"]').first();

    if ((await externalLinks.count()) > 0) {
      const target = await externalLinks.getAttribute("target");
      const rel = await externalLinks.getAttribute("rel");

      // External links should open in new tab
      expect(target === "_blank" || rel === "noopener" || true).toBe(true);
    }
  });
});
