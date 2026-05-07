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
import {
  isRedirectOnlyPage,
  normalizePath,
  shouldIncludeInSitemap,
  toSiteUrl
} from "../scripts/generate-sitemap";

describe("normalizePath", () => {
  it("preserves forward-slash paths", () => {
    expect(normalizePath("docs/index.html")).toBe("docs/index.html");
  });

  it("converts windows separators to forward slashes", () => {
    expect(normalizePath("docs\\features\\metrics\\index.html")).toBe(
      "docs/features/metrics/index.html"
    );
  });
});

describe("toSiteUrl", () => {
  it("maps the root index file to slash", () => {
    expect(toSiteUrl("index.html")).toBe("/");
  });

  it("maps nested index files to trailing-slash URLs", () => {
    expect(toSiteUrl("docs/features/metrics/index.html")).toBe(
      "/docs/features/metrics/"
    );
  });

  it("preserves non-index html filenames", () => {
    expect(toSiteUrl("news/archive.html")).toBe("/news/archive.html");
  });
});

describe("isRedirectOnlyPage", () => {
  it("detects javascript redirect pages", () => {
    expect(
      isRedirectOnlyPage(
        '<script>window.location.replace("/downloads/")</script>'
      )
    ).toBe(true);
  });

  it("detects meta refresh redirects", () => {
    expect(
      isRedirectOnlyPage(
        '<meta http-equiv="refresh" content="0; url=/downloads/" />'
      )
    ).toBe(true);
  });

  it("does not flag ordinary html pages", () => {
    expect(
      isRedirectOnlyPage("<html><body><h1>Apache ZooKeeper</h1></body></html>")
    ).toBe(false);
  });
});

describe("shouldIncludeInSitemap", () => {
  it("excludes explicitly ignored html paths", () => {
    expect(shouldIncludeInSitemap("404.html", "<html></html>")).toBe(false);
    expect(shouldIncludeInSitemap("404/index.html", "<html></html>")).toBe(
      false
    );
    expect(shouldIncludeInSitemap("__spa-fallback.html", "<html></html>")).toBe(
      false
    );
  });

  it("excludes redirect-only pages even if the path is not prelisted", () => {
    expect(
      shouldIncludeInSitemap(
        "downloads/latest/index.html",
        '<script>window.location.replace("/releases/")</script>'
      )
    ).toBe(false);
  });

  it("includes normal prerendered pages", () => {
    expect(
      shouldIncludeInSitemap(
        "docs/features/metrics/index.html",
        "<html><body><h1>Metrics</h1></body></html>"
      )
    ).toBe(true);
  });
});
