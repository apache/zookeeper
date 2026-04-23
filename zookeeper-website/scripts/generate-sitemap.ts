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

import { access, glob, readFile, stat, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { join } from "node:path";
import { SitemapStream, streamToPromise } from "sitemap";

const ROOT = join(import.meta.dirname, "..");
const BUILD_DIR = join(ROOT, "build", "client");
const SITEMAP_PATH = join(BUILD_DIR, "sitemap.xml");
const SITE_URL = "https://zookeeper.apache.org";

const EXCLUDED_HTML_PATHS = new Set([
  "404.html",
  "404/index.html",
  "__spa-fallback.html"
]);

const REDIRECT_PAGE_PATTERNS = [
  /window\.location\.replace\(/i,
  /http-equiv=["']refresh/i,
  />Redirecting to .*?If it does not happen automatically,/is
];

interface SitemapEntry {
  url: string;
  lastmod: string;
}

export function normalizePath(relativePath: string): string {
  return relativePath.replaceAll("\\", "/");
}

export function toSiteUrl(relativePath: string): string {
  if (relativePath === "index.html") {
    return "/";
  }

  if (relativePath.endsWith("/index.html")) {
    return `/${relativePath.slice(0, -"/index.html".length)}/`;
  }

  return `/${relativePath}`;
}

export function isRedirectOnlyPage(html: string): boolean {
  return REDIRECT_PAGE_PATTERNS.some((pattern) => pattern.test(html));
}

export function shouldIncludeInSitemap(
  relativePath: string,
  html: string
): boolean {
  if (EXCLUDED_HTML_PATHS.has(relativePath)) {
    return false;
  }

  return !isRedirectOnlyPage(html);
}

export async function collectSitemapEntries(): Promise<SitemapEntry[]> {
  const entries: SitemapEntry[] = [];

  for await (const htmlPath of glob("**/*.html", { cwd: BUILD_DIR })) {
    const relativePath = normalizePath(htmlPath);
    const filePath = join(BUILD_DIR, relativePath);
    const html = await readFile(filePath, "utf8");

    if (!shouldIncludeInSitemap(relativePath, html)) {
      continue;
    }

    const { mtime } = await stat(filePath);

    entries.push({
      url: toSiteUrl(relativePath),
      lastmod: mtime.toISOString()
    });
  }

  entries.sort((left, right) => left.url.localeCompare(right.url));
  return entries;
}

export async function main() {
  await access(BUILD_DIR);

  const entries = await collectSitemapEntries();
  const sitemap = new SitemapStream({ hostname: SITE_URL });

  for (const entry of entries) {
    sitemap.write(entry);
  }

  sitemap.end();

  const xml = (await streamToPromise(sitemap)).toString();
  await writeFile(SITEMAP_PATH, xml);

  console.log(
    `Generated sitemap with ${entries.length} URLs at ${SITEMAP_PATH}`
  );
}

const isDirectRun = process.argv[1] === fileURLToPath(import.meta.url);

if (isDirectRun) {
  await main();
}
