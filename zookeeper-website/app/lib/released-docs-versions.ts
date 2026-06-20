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

export const LEGACY_RELEASED_DOC_VERSIONS = new Set([
  "3.1.2",
  "3.2.2",
  "3.3.2",
  "3.3.3",
  "3.3.4",
  "3.3.5",
  "3.3.6",
  "3.4.0",
  "3.4.1",
  "3.4.2",
  "3.4.3",
  "3.4.4",
  "3.4.5",
  "3.4.6",
  "3.4.7",
  "3.4.8",
  "3.4.9",
  "3.4.10",
  "3.4.11",
  "3.4.12",
  "3.4.13",
  "3.4.14",
  "3.5.0-alpha",
  "3.5.1-alpha",
  "3.5.2-alpha",
  "3.5.3-beta",
  "3.5.4-beta",
  "3.5.5",
  "3.5.7",
  "3.5.8",
  "3.5.9",
  "3.5.10",
  "3.6.0",
  "3.6.1",
  "3.6.2",
  "3.6.3",
  "3.6.4",
  "3.7.0",
  "3.7.1",
  "3.7.2",
  "3.8.0",
  "3.8.1",
  "3.8.2",
  "3.8.3",
  "3.8.4",
  "3.8.5",
  "3.8.6",
  "3.9.0",
  "3.9.1",
  "3.9.2",
  "3.9.3",
  "3.9.4"
]);

export const REACT_ROUTER_RELEASED_DOC_VERSIONS = new Set<string>();

export const RAW_RELEASED_DOC_VERSIONS: string[] = [
  ...new Set([
    ...LEGACY_RELEASED_DOC_VERSIONS,
    ...REACT_ROUTER_RELEASED_DOC_VERSIONS
  ])
];

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

/**
 * All released documentation versions available under /doc/.
 * Maintained manually because archived docs live in the asf-site branch.
 */
export const RELEASED_DOC_VERSIONS: string[] = sortVersionsDesc(
  RAW_RELEASED_DOC_VERSIONS
);

export function getReleasedDocUrl(version: string): string {
  const basePath = `/doc/r${version}`;
  return LEGACY_RELEASED_DOC_VERSIONS.has(version)
    ? `${basePath}/index.html`
    : `${basePath}/`;
}

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
