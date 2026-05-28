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

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { tmpdir } from "node:os";
import {
  parseVersion,
  verifyArchiveOutput
} from "../scripts/build-docs-archive";

let tempDir: string;

async function writeFixtureFile(relativePath: string, content: string) {
  const filePath = join(tempDir, relativePath);
  await mkdir(dirname(filePath), { recursive: true });
  await writeFile(filePath, content);
}

async function writeValidArchiveFixture() {
  await writeFixtureFile(
    "index.html",
    '<html><script>window.location.replace("/")</script></html>'
  );
  await writeFixtureFile(
    "docs/index.html",
    '<html><a href="/released-docs/r3.9.6/docs/developer/programmers-guide/">Guide</a></html>'
  );
  await writeFixtureFile(
    "docs/developer/programmers-guide/index.html",
    '<html><img src="/released-docs/r3.9.6/docs-images/zkservice.jpg"></html>'
  );
  await writeFixtureFile("api/search", "{}");
  await writeFixtureFile(".htaccess", "RewriteEngine On");
}

beforeEach(async () => {
  tempDir = await mkdtemp(join(tmpdir(), "zk-docs-archive-test-"));
  vi.spyOn(console, "log").mockImplementation(() => undefined);
});

afterEach(async () => {
  vi.restoreAllMocks();
  await rm(tempDir, { recursive: true, force: true });
});

describe("parseVersion", () => {
  it("accepts stable and prerelease version strings", () => {
    expect(parseVersion("3.9.6")).toBe("3.9.6");
    expect(parseVersion("3.10.0-alpha")).toBe("3.10.0-alpha");
    expect(parseVersion("3.10.0-beta")).toBe("3.10.0-beta");
  });

  it("trims surrounding whitespace", () => {
    expect(parseVersion(" 3.9.6 ")).toBe("3.9.6");
  });

  it("rejects missing or malformed versions", () => {
    expect(() => parseVersion(undefined)).toThrow(/build:docs-archive/);
    expect(() => parseVersion("")).toThrow(/build:docs-archive/);
    expect(() => parseVersion("v3.9.6")).toThrow(/build:docs-archive/);
    expect(() => parseVersion("3.9")).toThrow(/build:docs-archive/);
    expect(() => parseVersion("3.9.6-rc1")).toThrow(/build:docs-archive/);
  });
});

describe("verifyArchiveOutput", () => {
  it("accepts an archive with docs under /docs and redirected landing pages", async () => {
    await writeValidArchiveFixture();

    await expect(verifyArchiveOutput(tempDir)).resolves.toBeUndefined();
  });

  it("rejects an archive without the docs index page", async () => {
    await writeFixtureFile(
      "docs/developer/programmers-guide/index.html",
      "<html>Guide</html>"
    );
    await writeFixtureFile("api/search", "{}");

    await expect(verifyArchiveOutput(tempDir)).rejects.toThrow(
      /missing docs\/index\.html/
    );
  });

  it("rejects an archive without nested docs pages", async () => {
    await writeFixtureFile(
      "index.html",
      '<html><script>window.location.replace("/")</script></html>'
    );
    await writeFixtureFile("docs/index.html", "<html>Docs</html>");
    await writeFixtureFile("api/search", "{}");
    await writeFixtureFile(".htaccess", "RewriteEngine On");

    await expect(verifyArchiveOutput(tempDir)).rejects.toThrow(
      /missing nested docs pages/
    );
  });

  it("rejects a missing search resource", async () => {
    await writeValidArchiveFixture();
    await rm(join(tempDir, "api/search"));

    await expect(verifyArchiveOutput(tempDir)).rejects.toThrow(
      /missing api\/search/
    );
  });

  it("rejects root-relative docs URLs in html", async () => {
    await writeValidArchiveFixture();
    await writeFixtureFile(
      "docs/developer/programmers-guide/index.html",
      '<html><a href="/docs/admin-ops/cli">CLI</a></html>'
    );

    await expect(verifyArchiveOutput(tempDir)).rejects.toThrow(
      /root-relative docs URL/
    );
  });

  it("rejects root-relative docs URLs in css", async () => {
    await writeValidArchiveFixture();
    await writeFixtureFile(
      "assets/app.css",
      '.hero { background: url("/docs-images/zkservice.jpg"); }'
    );

    await expect(verifyArchiveOutput(tempDir)).rejects.toThrow(
      /root-relative docs URL/
    );
  });

  it("allows route paths serialized into prerendered data", async () => {
    await writeValidArchiveFixture();
    await writeFixtureFile(
      "docs/index.html",
      '<html><script>window.__reactRouterContext.streamController.enqueue("\\"/docs\\"")</script></html>'
    );

    await expect(verifyArchiveOutput(tempDir)).resolves.toBeUndefined();
  });
});
