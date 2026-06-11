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

import { source } from "@/lib/source";
import { CURRENT_DOCS_PATH, isExternalHref } from "@/lib/docs-paths";
import type { InferPageType } from "fumadocs-core/source";

// llms-full.txt is emitted only by the docs build, where pages are served under
// /doc/rX/ and Fumadocs returns root-relative URLs (e.g. /admin-ops/jmx). The
// static .txt therefore needs the versioned public base from Vite.
function getDocsUrlBase(): string {
  return import.meta.env.BASE_URL === "/"
    ? CURRENT_DOCS_PATH
    : import.meta.env.BASE_URL.slice(0, -1);
}

export async function loader() {
  const scan = source.getPages().map(getLLMText);
  const scanned = await Promise.all(scan);

  return new Response(scanned.join("\n\n"));
}

export async function getLLMText(page: InferPageType<typeof source>) {
  const processed = await page.data.getText("processed");
  return `# ${page.data.title} (${getDocsUrlBase()}${page.url})
${resolveLLMTextLinks(processed, page.data.extractedReferences)}`;
}

export function resolveLLMTextLinks(
  text: string,
  references: Array<{ href: string }> = []
): string {
  const base = getDocsUrlBase();
  let resolved = text;

  for (const { href } of references) {
    if (!href.startsWith("/") || isExternalHref(href)) {
      continue;
    }

    const docsHref = `${base}${href}`;

    // Processed MDX is plain text with markdown links from our docs, e.g.
    //   [JMX](/admin-ops/jmx)
    //   [Advanced Configuration](/admin-ops/.../configuration-parameters#advanced-configuration)
    //
    // .replace() swaps only its match — "(" + path, not the closing ")":
    //
    //   [JMX](/admin-ops/jmx)
    //        └──── match ───┘
    //   → [JMX](/doc/r3.9.5/admin-ops/jmx)
    //
    // Lookahead (?=...) checks ")" comes next (also supports rare ` "title")` links we don't use).
    resolved = resolved.replace(
      new RegExp(`\\(${escapeRegExp(href)}(?=(?:\\s+["'][^)]*["'])?\\))`, "g"),
      `(${docsHref}`
    );
  }

  return resolved;
}

function escapeRegExp(value: string): string {
  // "#" is special in regex (start of comment). Escape it in the pattern only:
  //   in:  /admin-ops/.../configuration-parameters#advanced-configuration
  //   out: /admin-ops/.../configuration-parameters\#advanced-configuration  (RegExp string)
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
