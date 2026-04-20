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
import { readdirSync } from "fs";
import { resolve } from "path";
import { RAW_RELEASED_DOC_VERSIONS } from "virtual:released-docs-versions";
import {
  RELEASED_DOC_VERSIONS,
  sortVersionsDesc
} from "@/lib/released-docs-versions";

// Read the real filesystem so we can cross-check the virtual module output.
const RELEASED_DOCS_DIR = resolve(__dirname, "../public/released-docs");
const ACTUAL_FOLDERS = readdirSync(RELEASED_DOCS_DIR, { withFileTypes: true })
  .filter((d) => d.isDirectory() && d.name.startsWith("r"))
  .map((d) => d.name.slice(1)); // strip leading "r"

describe("virtual:released-docs-versions plugin", () => {
  it("exposes a non-empty array", () => {
    expect(Array.isArray(RAW_RELEASED_DOC_VERSIONS)).toBe(true);
    expect(RAW_RELEASED_DOC_VERSIONS.length).toBeGreaterThan(0);
  });

  it("matches exactly the directories present in public/released-docs/", () => {
    expect(RAW_RELEASED_DOC_VERSIONS.slice().sort()).toEqual(
      ACTUAL_FOLDERS.slice().sort()
    );
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

  it("only includes directory entries, not loose files", () => {
    // Every raw entry must correspond to a directory named r{version}
    const folderSet = new Set(ACTUAL_FOLDERS);
    RAW_RELEASED_DOC_VERSIONS.forEach((v) => {
      expect(folderSet.has(v)).toBe(true);
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

  it("first entry is the numerically highest version", () => {
    const [first, ...rest] = RELEASED_DOC_VERSIONS;
    const refirst = sortVersionsDesc([first, ...rest])[0];
    expect(first).toBe(refirst);
  });

  it("last entry is the numerically lowest version", () => {
    const sorted = sortVersionsDesc([...RELEASED_DOC_VERSIONS]);
    expect(RELEASED_DOC_VERSIONS.at(-1)).toBe(sorted.at(-1));
  });

  it("each consecutive pair is in descending order", () => {
    for (let i = 0; i < RELEASED_DOC_VERSIONS.length - 1; i++) {
      const [a, b] = [RELEASED_DOC_VERSIONS[i], RELEASED_DOC_VERSIONS[i + 1]];
      const [first] = sortVersionsDesc([a, b]);
      expect(first).toBe(a);
    }
  });
});
