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

import { describe, expect, it } from "vitest";
import { CURRENT_DOCS_PATH } from "@/lib/docs-paths";
import {
  resolveLLMTextLinks,
  toAbsoluteDocsUrl
} from "@/routes/_api/llms-full";

describe("toAbsoluteDocsUrl", () => {
  it("prefixes root-relative docs paths (docs build shape)", () => {
    expect(toAbsoluteDocsUrl("/admin-ops/jmx")).toBe(
      `${CURRENT_DOCS_PATH}/admin-ops/jmx`
    );
  });

  it("maps the root-relative index to the docs base", () => {
    expect(toAbsoluteDocsUrl("/")).toBe(`${CURRENT_DOCS_PATH}/`);
  });

  it("is idempotent for already-prefixed URLs (dev shape)", () => {
    expect(toAbsoluteDocsUrl(`${CURRENT_DOCS_PATH}/admin-ops/jmx`)).toBe(
      `${CURRENT_DOCS_PATH}/admin-ops/jmx`
    );
  });

  it("does not double-prefix the index page URL equal to the base", () => {
    expect(toAbsoluteDocsUrl(CURRENT_DOCS_PATH)).toBe(CURRENT_DOCS_PATH);
  });
});

describe("resolveLLMTextLinks", () => {
  it("prefixes Fumadocs-extracted root-relative link references", () => {
    const text = [
      "[CLI](/admin-ops/cli)",
      "[Mailing Lists](/mailing-lists)",
      "[API](/apidocs/zookeeper-server/index.html)",
      '[Titled](/developer/programmers-guide "Programmers Guide")'
    ].join("\n");

    expect(
      resolveLLMTextLinks(text, [
        { href: "/admin-ops/cli" },
        { href: "/mailing-lists" },
        { href: "/apidocs/zookeeper-server/index.html" },
        { href: "/developer/programmers-guide" }
      ])
    ).toBe(
      [
        `[CLI](${CURRENT_DOCS_PATH}/admin-ops/cli)`,
        `[Mailing Lists](${CURRENT_DOCS_PATH}/mailing-lists)`,
        `[API](${CURRENT_DOCS_PATH}/apidocs/zookeeper-server/index.html)`,
        `[Titled](${CURRENT_DOCS_PATH}/developer/programmers-guide "Programmers Guide")`
      ].join("\n")
    );
  });

  it("leaves images, anchors, and external links unchanged", () => {
    const text = [
      "![Service](/docs-images/zkservice.jpg)",
      "[Anchor](#local)",
      "[External](https://zookeeper.apache.org/)"
    ].join("\n");

    expect(
      resolveLLMTextLinks(text, [
        { href: "#local" },
        { href: "https://zookeeper.apache.org/" }
      ])
    ).toBe(text);
  });

  it("rewrites links inside MDX JSX text blocks when Fumadocs extracted them", () => {
    const text = `<Callout>
  See [Dynamic Reconfiguration](/admin-ops/dynamic-reconfiguration).
</Callout>`;

    expect(
      resolveLLMTextLinks(text, [
        { href: "/admin-ops/dynamic-reconfiguration" }
      ])
    ).toContain(
      `[Dynamic Reconfiguration](${CURRENT_DOCS_PATH}/admin-ops/dynamic-reconfiguration)`
    );
  });
});
