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
import {
  getReleasedDocUrl,
  LEGACY_RELEASED_DOC_VERSIONS,
  RAW_RELEASED_DOC_VERSIONS,
  REACT_ROUTER_RELEASED_DOC_VERSIONS,
  RELEASED_DOC_VERSIONS,
  sortVersionsDesc
} from "@/lib/released-docs-versions";

const MOCK_RELEASED_DOC_VERSIONS = ["3.9.4", "3.10.0", "3.9.0-beta", "3.8.12"];

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

describe("RAW_RELEASED_DOC_VERSIONS", () => {
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

  it("contains the first and latest archived documentation versions", () => {
    expect(RAW_RELEASED_DOC_VERSIONS).toContain("3.1.2");
    expect(RAW_RELEASED_DOC_VERSIONS).toContain("3.9.4");
  });

  it("contains every archived docs version exactly once", () => {
    expect(new Set(RAW_RELEASED_DOC_VERSIONS).size).toBe(
      RAW_RELEASED_DOC_VERSIONS.length
    );
    expect(RAW_RELEASED_DOC_VERSIONS.length).toBe(52);
  });

  it("combines legacy and React Router archive versions", () => {
    expect(RAW_RELEASED_DOC_VERSIONS).toEqual([
      ...LEGACY_RELEASED_DOC_VERSIONS,
      ...REACT_ROUTER_RELEASED_DOC_VERSIONS
    ]);
  });

  it("keeps legacy and React Router archive version sets distinct", () => {
    const legacyVersions = new Set(LEGACY_RELEASED_DOC_VERSIONS);
    REACT_ROUTER_RELEASED_DOC_VERSIONS.forEach((version) => {
      expect(legacyVersions.has(version)).toBe(false);
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

describe("getReleasedDocUrl", () => {
  it("links legacy static archives to index.html", () => {
    expect(getReleasedDocUrl("3.9.4")).toBe("/doc/r3.9.4/index.html");
  });

  it("links new React Router archives to /doc/r<version>/", () => {
    expect(getReleasedDocUrl("3.10.0")).toBe("/doc/r3.10.0/");
  });
});
