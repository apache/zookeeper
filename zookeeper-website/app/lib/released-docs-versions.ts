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

type PreRelease = "alpha" | "beta" | "stable";

interface ParsedVersion {
  major: number;
  minor: number;
  patch: number;
  preRelease: PreRelease;
  raw: string;
}

function parseVersion(version: string): ParsedVersion {
  const match = version.match(/^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta))?/);
  if (!match) {
    return { major: 0, minor: 0, patch: 0, preRelease: "stable", raw: version };
  }
  const preRelease = (match[4] as PreRelease | undefined) ?? "stable";
  return {
    major: parseInt(match[1], 10),
    minor: parseInt(match[2], 10),
    patch: parseInt(match[3], 10),
    preRelease,
    raw: version
  };
}

const preReleaseOrder: Record<PreRelease, number> = {
  stable: 2,
  beta: 1,
  alpha: 0
};

export function sortVersionsDesc(versions: string[]): string[] {
  return [...versions].sort((a, b) => {
    const pa = parseVersion(a);
    const pb = parseVersion(b);
    if (pb.major !== pa.major) return pb.major - pa.major;
    if (pb.minor !== pa.minor) return pb.minor - pa.minor;
    if (pb.patch !== pa.patch) return pb.patch - pa.patch;
    return preReleaseOrder[pb.preRelease] - preReleaseOrder[pa.preRelease];
  });
}

import { RAW_RELEASED_DOC_VERSIONS } from "virtual:released-docs-versions";

/**
 * All released documentation versions available under /released-docs/.
 * Derived from the actual folder names at build time, sorted newest to oldest.
 */
export const RELEASED_DOC_VERSIONS: string[] = sortVersionsDesc(
  RAW_RELEASED_DOC_VERSIONS
);

export function getReleasedDocVersions(): string[] {
  if (typeof window !== "undefined") {
    const override = window.localStorage.getItem(
      "__released_doc_versions_override__"
    );
    if (override) {
      try {
        const parsed = JSON.parse(override);
        if (
          Array.isArray(parsed) &&
          parsed.every((value) => typeof value === "string")
        ) {
          return sortVersionsDesc(parsed);
        }
      } catch {
        // Ignore invalid test overrides and fall back to build-time data.
      }
    }
  }

  return RELEASED_DOC_VERSIONS;
}
