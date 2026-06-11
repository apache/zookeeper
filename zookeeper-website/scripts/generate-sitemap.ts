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

// Generates sitemap.xml. Landing and docs are independent builds, so the sitemap
// is upserted per scope against a committed merge base (public/sitemap.xml):
//   --scope landing  refreshes landing URLs, keeps the docs URLs from the base
//   --scope docs     refreshes current-version docs URLs, keeps landing URLs
//   --scope all      (the combined `npm run build`) replaces everything
// URLs are partitioned into "docs" (/doc/...) vs "landing" by prefix. Archived
// doc versions live only on asf-site and are intentionally excluded. The result
// is written to both public/sitemap.xml (committed source of truth) and
// build/client/sitemap.xml (deployed). It lists URLs only (no lastmod), so it
// changes only when the set of URLs changes.

import { access, glob, readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { join } from "node:path";
import { SitemapStream, streamToPromise } from "sitemap";

const ROOT = join(import.meta.dirname, "..");
const BUILD_DIR = join(ROOT, "build", "client");
// Deployed copy (shipped from build/client) and the committed source of truth
// (public/sitemap.xml). The committed file is the persistent merge base so that
// partial builds (landing-only or docs-only) keep the other slice's URLs.
const BUILD_SITEMAP_PATH = join(BUILD_DIR, "sitemap.xml");
const PUBLIC_SITEMAP_PATH = join(ROOT, "public", "sitemap.xml");
const SITE_URL = "https://zookeeper.apache.org";

const EXCLUDED_HTML_PATHS = new Set([
  "404.html",
  "404/index.html",
  "__spa-fallback.html",
  // The /doc redirect is a client-side <Navigate> with no redirect marker in its
  // prerendered HTML, so it must be excluded explicitly (and it only exists in
  // builds that include landing, which would otherwise diverge from docs builds).
  "doc/index.html"
]);

const REDIRECT_PAGE_PATTERNS = [
  /window\.location\.replace\(/i,
  /http-equiv=["']refresh/i,
  />Redirecting to .*?If it does not happen automatically,/is
];

// A build only ever produces one slice of the site, so the sitemap is upserted
// per scope: "landing" and "docs" refresh just their own URLs and keep the rest
// from the committed base; "all" (the combined build) replaces everything.
export type SitemapScope = "landing" | "docs" | "all";

export function parseSitemapScope(args = process.argv.slice(2)): SitemapScope {
  const flagIndex = args.findIndex(
    (arg) => arg === "--scope" || arg.startsWith("--scope=")
  );
  if (flagIndex === -1) {
    return "all";
  }

  const flag = args[flagIndex];
  const value = flag.includes("=") ? flag.split("=")[1] : args[flagIndex + 1];

  if (value === "landing" || value === "docs" || value === "all") {
    return value;
  }

  throw new Error(
    `Invalid --scope "${value ?? ""}". Expected one of: landing, docs, all.`
  );
}

export function isDocsUrl(url: string): boolean {
  return url === "/doc/" || url.startsWith("/doc/");
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

// Merges freshly scanned URLs with the committed base. The rebuilt scope's URLs
// come from `fresh`; the other scope is preserved from `base`. "all" replaces
// everything.
export function mergeSitemapUrls(
  base: string[],
  fresh: string[],
  scope: SitemapScope
): string[] {
  const retained =
    scope === "all"
      ? []
      : base.filter((url) =>
          scope === "landing" ? isDocsUrl(url) : !isDocsUrl(url)
        );

  const rebuilt =
    scope === "all"
      ? fresh
      : fresh.filter((url) =>
          scope === "landing" ? !isDocsUrl(url) : isDocsUrl(url)
        );

  return [...new Set([...retained, ...rebuilt])].sort((left, right) =>
    left.localeCompare(right)
  );
}

export async function collectSitemapUrls(): Promise<string[]> {
  const urls: string[] = [];

  for await (const htmlPath of glob("**/*.html", { cwd: BUILD_DIR })) {
    const relativePath = normalizePath(htmlPath);
    const html = await readFile(join(BUILD_DIR, relativePath), "utf8");

    if (!shouldIncludeInSitemap(relativePath, html)) {
      continue;
    }

    urls.push(toSiteUrl(relativePath));
  }

  return urls;
}

export async function readExistingSitemapUrls(path: string): Promise<string[]> {
  let xml: string;
  try {
    xml = await readFile(path, "utf8");
  } catch {
    return [];
  }

  const urls: string[] = [];
  for (const match of xml.matchAll(/<loc>(.*?)<\/loc>/g)) {
    const loc = match[1];
    urls.push(
      loc.startsWith(SITE_URL) ? loc.slice(SITE_URL.length) || "/" : loc
    );
  }

  return urls;
}

async function toSitemapXml(urls: string[]): Promise<string> {
  const sitemap = new SitemapStream({ hostname: SITE_URL });

  for (const url of urls) {
    sitemap.write({ url });
  }

  sitemap.end();
  return (await streamToPromise(sitemap)).toString();
}

export async function main() {
  const scope = parseSitemapScope();
  await access(BUILD_DIR);

  const fresh = await collectSitemapUrls();
  const base = await readExistingSitemapUrls(PUBLIC_SITEMAP_PATH);
  const urls = mergeSitemapUrls(base, fresh, scope);

  const xml = await toSitemapXml(urls);
  await writeFile(PUBLIC_SITEMAP_PATH, xml);
  await writeFile(BUILD_SITEMAP_PATH, xml);

  console.log(
    `Generated sitemap (scope: ${scope}) with ${urls.length} URLs at ${BUILD_SITEMAP_PATH}`
  );
}

const isDirectRun = process.argv[1] === fileURLToPath(import.meta.url);

if (isDirectRun) {
  await main();
}
