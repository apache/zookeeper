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
  createArchiveHtaccessContent,
  parseDocsBuildArgs,
  toArchiveHtmlPathFromMdx,
  verifyArchiveOutput
} from "../scripts/build-docs";

let tempDir: string;
let tempDocsDir: string;

async function writeFixtureFile(relativePath: string, content: string) {
  const filePath = join(tempDir, relativePath);
  await mkdir(dirname(filePath), { recursive: true });
  await writeFile(filePath, content);
}

async function writeDocsSourceFile(
  relativePath: string,
  content = "---\n---\n"
) {
  const filePath = join(tempDocsDir, relativePath);
  await mkdir(dirname(filePath), { recursive: true });
  await writeFile(filePath, content);
}

async function writeValidArchiveFixture() {
  await writeDocsSourceFile("index.mdx");
  await writeDocsSourceFile("developer/programmers-guide/index.mdx");
  await writeFixtureFile(
    "index.html",
    '<html><a href="/doc/r3.9.6/developer/programmers-guide/">Guide</a></html>'
  );
  await writeFixtureFile(
    "developer/programmers-guide/index.html",
    '<html><img src="/doc/r3.9.6/docs-images/zkservice.jpg"></html>'
  );
  await writeFixtureFile("api/search", "{}");
  await writeFixtureFile("llms-full.txt", "# Docs\n");
  await writeFixtureFile(".htaccess", "RewriteEngine On");
}

beforeEach(async () => {
  tempDir = await mkdtemp(join(tmpdir(), "zk-docs-archive-test-"));
  tempDocsDir = await mkdtemp(join(tmpdir(), "zk-docs-source-test-"));
  vi.spyOn(console, "log").mockImplementation(() => undefined);
});

afterEach(async () => {
  vi.restoreAllMocks();
  await rm(tempDir, { recursive: true, force: true });
  await rm(tempDocsDir, { recursive: true, force: true });
});

describe("parseDocsBuildArgs", () => {
  it("accepts no arguments", () => {
    expect(() => parseDocsBuildArgs([])).not.toThrow();
  });

  it("rejects CLI version overrides", () => {
    expect(() => parseDocsBuildArgs(["3.9.6"])).toThrow(/build:docs/);
    expect(() => parseDocsBuildArgs(["--archive"])).toThrow(/build:docs/);
  });
});

describe("toArchiveHtmlPathFromMdx", () => {
  it("maps docs source files to archive html paths", () => {
    expect(toArchiveHtmlPathFromMdx("index.mdx")).toBe("index.html");
    expect(toArchiveHtmlPathFromMdx("overview/quick-start.mdx")).toBe(
      "overview/quick-start/index.html"
    );
    expect(
      toArchiveHtmlPathFromMdx("developer/programmers-guide/index.mdx")
    ).toBe("developer/programmers-guide/index.html");
  });
});

describe("verifyArchiveOutput", () => {
  it("accepts an archive with docs at the archive root", async () => {
    await writeValidArchiveFixture();

    await expect(
      verifyArchiveOutput(tempDir, tempDocsDir)
    ).resolves.toBeUndefined();
  });

  it("rejects an archive without the docs index page", async () => {
    await writeDocsSourceFile("index.mdx");
    await writeDocsSourceFile("developer/programmers-guide/index.mdx");
    await writeFixtureFile(
      "developer/programmers-guide/index.html",
      "<html>Guide</html>"
    );
    await writeFixtureFile("api/search", "{}");

    await expect(verifyArchiveOutput(tempDir, tempDocsDir)).rejects.toThrow(
      /missing index\.html/
    );
  });

  it("rejects an archive without nested docs pages", async () => {
    await writeDocsSourceFile("index.mdx");
    await writeDocsSourceFile("developer/programmers-guide/index.mdx");
    await writeFixtureFile(
      "index.html",
      '<html><script>window.location.replace("/")</script></html>'
    );
    await writeFixtureFile("api/search", "{}");
    await writeFixtureFile("llms-full.txt", "# Docs\n");
    await writeFixtureFile(".htaccess", "RewriteEngine On");

    await expect(verifyArchiveOutput(tempDir, tempDocsDir)).rejects.toThrow(
      /missing nested docs pages/
    );
  });

  it("rejects a missing search resource", async () => {
    await writeValidArchiveFixture();
    await rm(join(tempDir, "api/search"));

    await expect(verifyArchiveOutput(tempDir, tempDocsDir)).rejects.toThrow(
      /missing api\/search/
    );
  });

  it("rejects a missing llms-full.txt resource", async () => {
    await writeValidArchiveFixture();
    await rm(join(tempDir, "llms-full.txt"));

    await expect(verifyArchiveOutput(tempDir, tempDocsDir)).rejects.toThrow(
      /missing llms-full\.txt/
    );
  });

  it("rejects a missing generated page for an mdx source file", async () => {
    await writeValidArchiveFixture();
    await writeDocsSourceFile("overview/quick-start.mdx");

    await expect(verifyArchiveOutput(tempDir, tempDocsDir)).rejects.toThrow(
      /missing docs page overview\/quick-start\/index\.html/
    );
  });

  it("rejects root-relative docs URLs in html", async () => {
    await writeValidArchiveFixture();
    await writeFixtureFile(
      "developer/programmers-guide/index.html",
      '<html><a href="/admin-ops/cli">CLI</a></html>'
    );

    await expect(verifyArchiveOutput(tempDir, tempDocsDir)).rejects.toThrow(
      /root-relative docs URL/
    );
  });

  it("rejects root-relative docs URLs in css", async () => {
    await writeValidArchiveFixture();
    await writeFixtureFile(
      "assets/app.css",
      '.hero { background: url("/docs-images/zkservice.jpg"); }'
    );

    await expect(verifyArchiveOutput(tempDir, tempDocsDir)).rejects.toThrow(
      /root-relative docs URL/
    );
  });

  it("allows route paths serialized into prerendered data", async () => {
    await writeValidArchiveFixture();
    await writeFixtureFile(
      "index.html",
      '<html><script>window.__reactRouterContext.streamController.enqueue("\\"/admin-ops/cli\\"")</script></html>'
    );

    await expect(
      verifyArchiveOutput(tempDir, tempDocsDir)
    ).resolves.toBeUndefined();
  });
});

describe("createArchiveHtaccessContent", () => {
  it("redirects only missing archive-local paths to matching live-site paths", () => {
    const htaccess = createArchiveHtaccessContent();

    expect(htaccess).toContain("RewriteEngine On");
    expect(htaccess).toContain("RewriteCond %{REQUEST_FILENAME} !-f");
    expect(htaccess).toContain("RewriteCond %{REQUEST_FILENAME} !-d");
    expect(htaccess).toContain("RewriteRule ^(.*)$ /$1 [R=302,L]");
    expect(htaccess).not.toContain("/doc/r");
  });
});
