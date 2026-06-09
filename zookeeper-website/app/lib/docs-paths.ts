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

// Imported via a relative path (not the `@` alias) so this module can also be
// loaded by Node-only configs/scripts run via tsx, which does not resolve `@`.
import { CURRENT_VERSION } from "./current-version";

export function formatDocsBase(version: string): string {
  return `/doc/r${version}`;
}

export const CURRENT_DOCS_PATH = formatDocsBase(CURRENT_VERSION);

// Docs base flows across two execution contexts:
//   ZOOKEEPER_DOCS_ARCHIVE_BASE (process.env, Node configs/scripts)
//     -> Vite `base`
//     -> import.meta.env.BASE_URL (bundled browser code, read below).
// It is the same value in both worlds; only the access mechanism differs.
export function getDocsBasePath(): string {
  // Live builds run at the site root, so internal docs links need the current
  // docs prefix. Archive builds already have /doc/rX/ in React Router basename,
  // so internal docs links should remain archive-local and unprefixed here.
  return import.meta.env.BASE_URL === "/" ? CURRENT_DOCS_PATH : "";
}

function isExternalHref(href: string): boolean {
  return /^(?:https?:|mailto:|#)/i.test(href);
}

export function resolveDocsHref(href: string): string {
  if (!href.startsWith("/") || isExternalHref(href)) {
    return href;
  }

  const base = getDocsBasePath();
  if (!base) {
    return href;
  }

  return `${base}${href}`;
}

// Fumadocs applies baseUrl to MDX pages in the sidebar, but not to custom meta.json
// links such as external:[API Docs](/apidocs/...).
export function resolveApidocsHref(href: string): string {
  return href.startsWith("/apidocs/") ? resolveDocsHref(href) : href;
}
