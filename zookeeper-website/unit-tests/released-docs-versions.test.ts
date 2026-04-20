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

import { describe, it, expect } from "vitest";
import { mkdtempSync, rmSync } from "fs";
import { join } from "path";
import { tmpdir } from "os";
import { RAW_RELEASED_DOC_VERSIONS } from "virtual:released-docs-versions";
import {
  RELEASED_DOC_VERSIONS,
  sortVersionsDesc
} from "@/lib/released-docs-versions";
import {
  extractReleasedDocsVersions,
  getReleasedDocsVersions
} from "../plugins/released-docs-versions";

const MOCK_RELEASED_DOC_VERSIONS = ["3.9.4", "3.10.0", "3.9.0-beta", "3.8.12"];

function mockDirEntry(name: string, isDirectory: boolean) {
  return {
    name,
    isDirectory: () => isDirectory
  };
}

describe("extractReleasedDocsVersions", () => {
  it("strips the leading 'r' prefix from mocked release directories", () => {
    const versions = extractReleasedDocsVersions([
      mockDirEntry("r3.9.4", true),
      mockDirEntry("r3.10.0-beta", true)
    ]);

    expect(versions).toEqual(["3.9.4", "3.10.0-beta"]);
  });

  it("ignores non-release folders and loose files from mocked entries", () => {
    const versions = extractReleasedDocsVersions([
      mockDirEntry("r3.9.4", true),
      mockDirEntry("draft-docs", true),
      mockDirEntry("r3.8.0", false),
      mockDirEntry("README.md", false)
    ]);

    expect(versions).toEqual(["3.9.4"]);
  });
});

describe("getReleasedDocsVersions", () => {
  it("returns an empty array when the released-docs directory is missing", () => {
    const missingDir = join(tmpdir(), `released-docs-missing-${Date.now()}`);

    expect(getReleasedDocsVersions(missingDir)).toEqual([]);
  });

  it("returns an empty array for an empty released-docs directory", () => {
    const emptyDir = mkdtempSync(join(tmpdir(), "released-docs-empty-"));

    try {
      expect(getReleasedDocsVersions(emptyDir)).toEqual([]);
    } finally {
      rmSync(emptyDir, { recursive: true, force: true });
    }
  });
});

describe("sortVersionsDesc with mocked released-docs versions", () => {
  it("places the numerically highest version first", () => {
    const sorted = sortVersionsDesc(MOCK_RELEASED_DOC_VERSIONS);
    expect(sorted[0]).toBe("3.10.0");
  });

  it("places the numerically lowest version last", () => {
    const sorted = sortVersionsDesc(MOCK_RELEASED_DOC_VERSIONS);
    expect(sorted.at(-1)).toBe("3.8.12");
  });

  it("keeps each consecutive pair in descending order", () => {
    const sorted = sortVersionsDesc(MOCK_RELEASED_DOC_VERSIONS);

    for (let i = 0; i < sorted.length - 1; i++) {
      const [a, b] = [sorted[i], sorted[i + 1]];
      const [first] = sortVersionsDesc([a, b]);
      expect(first).toBe(a);
    }
  });
});

describe("virtual:released-docs-versions plugin", () => {
  it("exposes an array", () => {
    expect(Array.isArray(RAW_RELEASED_DOC_VERSIONS)).toBe(true);
  });

  it("strips the leading 'r' prefix from every folder name", () => {
    RAW_RELEASED_DOC_VERSIONS.forEach((v) => {
      expect(v).not.toMatch(/^r/);
    });
  });

  it("contains no empty strings", () => {
    RAW_RELEASED_DOC_VERSIONS.forEach((v) => {
      expect(v.length).toBeGreaterThan(0);
    });
  });

  it("every entry looks like a valid version string", () => {
    RAW_RELEASED_DOC_VERSIONS.forEach((v) => {
      expect(v).toMatch(/^\d+\.\d+\.\d+/);
    });
  });
});

describe("RELEASED_DOC_VERSIONS (sorted output)", () => {
  it("equals sortVersionsDesc applied to the raw virtual-module data", () => {
    expect(RELEASED_DOC_VERSIONS).toEqual(
      sortVersionsDesc(RAW_RELEASED_DOC_VERSIONS)
    );
  });

  it("contains the same entries as RAW_RELEASED_DOC_VERSIONS, just reordered", () => {
    expect(RELEASED_DOC_VERSIONS.slice().sort()).toEqual(
      RAW_RELEASED_DOC_VERSIONS.slice().sort()
    );
  });
});
