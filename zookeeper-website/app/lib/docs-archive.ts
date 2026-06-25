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

// Archive builds set ZOOKEEPER_DOCS_ARCHIVE_BASE in process.env. Vite turns it
// into the app `base`, which bundled browser code reads back as
// import.meta.env.BASE_URL (see getDocsBasePath in docs-paths.ts). Node-side
// code (vite/react-router configs, scripts) reads it here via process.env;
// it is the same value in two execution contexts.
export const DOCS_ARCHIVE_BASE_ENV = "ZOOKEEPER_DOCS_ARCHIVE_BASE";

export function normalizeDocsArchiveBase(value?: string): string {
  const trimmed = value?.trim();
  if (!trimmed) {
    return "";
  }

  const withLeadingSlash = trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
  const withoutDuplicateSlashes = withLeadingSlash.replace(/\/+/g, "/");
  return withoutDuplicateSlashes.endsWith("/")
    ? withoutDuplicateSlashes
    : `${withoutDuplicateSlashes}/`;
}

// Single Node-side read of the archive base: owns the env-var name and the
// normalization. Used by the vite and react-router configs.
export function getDocsArchiveBase(): string {
  return normalizeDocsArchiveBase(process.env[DOCS_ARCHIVE_BASE_ENV]);
}

// The production landing build sets ZOOKEEPER_BUILD_TARGET=landing so the route
// table and prerender exclude docs. Archive/docs builds use the archive base
// instead; dev (`react-router dev`) sets neither and serves a combined site.
export const DOCS_BUILD_TARGET_ENV = "ZOOKEEPER_BUILD_TARGET";

export function getBuildTarget(): "landing" | undefined {
  return process.env[DOCS_BUILD_TARGET_ENV] === "landing"
    ? "landing"
    : undefined;
}
