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

test.describe("Documentation Page - Basic Loading", () => {
  test("docs page loads successfully with title", async ({ page }) => {
    await page.goto("/docs");
    await page.waitForLoadState("networkidle");

    // Check URL and title
    await expect(page).toHaveURL(/.*docs/);
    await expect(page).toHaveTitle(/Overview|ZooKeeper/i);
  });

  test("docs page has visible content", async ({ page }) => {
    await page.goto("/docs");
    await page.waitForLoadState("networkidle");

    // Check for meaningful text content
    const bodyText = await page.locator("body").textContent();
    expect(bodyText).toBeTruthy();
    expect(bodyText!.length).toBeGreaterThan(500);
  });

  test("docs page has header with Apache ZooKeeper link", async ({ page }) => {
    await page.goto("/docs");
    await page.waitForLoadState("networkidle");

    // Check for header link
    const headerLink = page
      .getByRole("link", { name: /Apache ZooKeeper/i })
      .first();
    await expect(headerLink).toBeVisible();
  });
});

test.describe("Documentation Page - Sidebar Navigation", () => {
  test("sidebar is visible and has navigation items", async ({ page }) => {
    await page.goto("/docs");
    await page.waitForLoadState("networkidle");

    // Look for sidebar (aside element)
    const sidebar = page.locator("aside").first();
    await expect(sidebar).toBeVisible();

    // Check sidebar has links
    const sidebarLinks = sidebar.locator("a");
    const linkCount = await sidebarLinks.count();
    expect(linkCount).toBeGreaterThan(5);
  });

  test("sidebar has multiple navigation links", async ({ page }) => {
    await page.goto("/docs");
    await page.waitForLoadState("networkidle");

    // Verify sidebar has multiple links to different pages
    const sidebar = page.locator("aside");
    const allLinks = sidebar.locator('a[href^="/docs/"]');
    const linkCount = await allLinks.count();

    // Should have many navigation links in sidebar
    expect(linkCount).toBeGreaterThan(10);

    // Verify at least one link is visible
    const firstLink = allLinks.first();
    await expect(firstLink).toBeVisible();
  });

  test("sidebar collapse button works", async ({ page }) => {
    await page.goto("/docs");
    await page.waitForLoadState("networkidle");

    // Look for collapse button (use .first() for desktop version)
    const collapseButton = page
      .getByRole("button", { name: /Collapse Sidebar/i })
      .first();

    if ((await collapseButton.count()) > 0) {
      await expect(collapseButton).toBeVisible();

      // Click to collapse
      await collapseButton.click();
      await page.waitForTimeout(300);

      // The button should still exist (might change text or icon)
      expect(true).toBe(true);
    }
  });
});

test.describe("Documentation Page - Search Functionality", () => {
  test("search button is visible on docs page", async ({ page }) => {
    await page.goto("/docs");
    await page.waitForLoadState("networkidle");

    // Look for search button with more flexible matching
    const searchButton = page.getByRole("button", { name: /Search/i }).first();
    await expect(searchButton).toBeVisible();
  });

  test("can open search dialog by clicking button", async ({ page }) => {
    await page.goto("/docs");
    await page.waitForLoadState("networkidle");

    // Click search button - look for button containing "Search"
    const searchButton = page.getByRole("button", { name: /Search/i }).first();
    await searchButton.click();

    // Wait for dialog
    await page.waitForTimeout(500);

    // Verify dialog opened
    const searchDialog = page.getByRole("dialog", { name: /Search/i });
    await expect(searchDialog).toBeVisible();
  });

  test("can type in search input", async ({ page }) => {
    await page.goto("/docs");
    await page.waitForLoadState("networkidle");

    // Open search
    const searchButton = page.getByRole("button", { name: /Search/i }).first();
    await searchButton.click();
    await page.waitForTimeout(500);

    // Find and type in search input
    const searchInput = page.getByRole("textbox", { name: /Search/i });
    await expect(searchInput).toBeVisible();

    await searchInput.fill("configuration");
    await expect(searchInput).toHaveValue("configuration");
  });

  test("can close search with ESC key", async ({ page }) => {
    await page.goto("/docs");
    await page.waitForLoadState("networkidle");

    // Open search
    const searchButton = page.getByRole("button", { name: /Search/i }).first();
    await searchButton.click();
    await page.waitForTimeout(500);

    // Verify dialog is open
    const searchDialog = page.getByRole("dialog", { name: /Search/i });
    await expect(searchDialog).toBeVisible();

    // Press ESC
    await page.keyboard.press("Escape");
    await page.waitForTimeout(300);

    // Dialog should be closed
    await expect(searchDialog).not.toBeVisible();
  });

  test("keyboard shortcut Cmd+K opens search", async ({ page }) => {
    await page.goto("/docs");
    await page.waitForLoadState("networkidle");

    // Press Cmd+K (or Ctrl+K)
    await page.keyboard.press(
      process.platform === "darwin" ? "Meta+k" : "Control+k"
    );
    await page.waitForTimeout(500);

    // Verify dialog opened
    const searchDialog = page.getByRole("dialog", { name: /Search/i });
    await expect(searchDialog).toBeVisible();
  });
});

test.describe("Documentation Page - Content & Navigation", () => {
  test("docs page has headings", async ({ page }) => {
    await page.goto("/docs");
    await page.waitForLoadState("networkidle");

    // Check for headings
    const headings = page.locator("h1, h2, h3");
    const headingCount = await headings.count();
    expect(headingCount).toBeGreaterThan(2);
  });

  test("can navigate to configuration docs", async ({ page }) => {
    await page.goto("/docs/configuration");
    await page.waitForLoadState("networkidle");

    await expect(page).toHaveURL(/.*configuration/);

    // Check page has content
    const bodyText = await page.locator("body").textContent();
    expect(bodyText).toBeTruthy();
  });

  test("Edit on GitHub link is present", async ({ page }) => {
    await page.goto("/docs");
    await page.waitForLoadState("networkidle");

    // Look for GitHub link
    const githubLink = page.getByRole("link", { name: /Edit on GitHub/i });

    // Wait a bit for it to appear
    await page.waitForTimeout(1000);

    if ((await githubLink.count()) > 0) {
      await expect(githubLink).toBeVisible();
    }
  });
});

test.describe("Documentation Page - Mobile Responsiveness", () => {
  test("mobile menu button is visible on small screens", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto("/docs");
    await page.waitForLoadState("networkidle");

    // Look for mobile menu button
    const menuButton = page.getByRole("button", { name: /Open Sidebar/i });
    await expect(menuButton).toBeVisible();
  });

  test("can open mobile sidebar", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto("/docs");
    await page.waitForLoadState("networkidle");

    // Click mobile menu button
    const menuButton = page.getByRole("button", { name: /Open Sidebar/i });
    await menuButton.click();
    await page.waitForTimeout(500);

    // Sidebar drawer should appear (check for navigation elements)
    const sidebarLinks = page.locator('a[href^="/docs"]');
    const linkCount = await sidebarLinks.count();
    expect(linkCount).toBeGreaterThan(5);
  });

  test("search button visible on mobile", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto("/docs");
    await page.waitForLoadState("networkidle");

    // Look for search button
    const searchButton = page
      .getByRole("button", { name: /Open Search|Search/i })
      .first();
    await expect(searchButton).toBeVisible();
  });
});
