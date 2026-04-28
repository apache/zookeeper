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

import { readdirSync, type Dirent } from "fs";
import { resolve } from "path";
import type { Plugin } from "vite";

const VIRTUAL_ID = "virtual:released-docs-versions";
const RESOLVED_ID = "\0" + VIRTUAL_ID;
const RELEASED_DOCS_DIR = resolve(__dirname, "../public/released-docs");

type ReleasedDocsDirEntry = Pick<Dirent, "name" | "isDirectory">;

export function extractReleasedDocsVersions(
  entries: ReleasedDocsDirEntry[]
): string[] {
  return entries
    .filter((entry) => entry.isDirectory() && entry.name.startsWith("r"))
    .map((entry) => entry.name.slice(1));
}

export function getReleasedDocsVersions(dir = RELEASED_DOCS_DIR): string[] {
  try {
    const entries = readdirSync(dir, { withFileTypes: true });
    return extractReleasedDocsVersions(entries);
  } catch (error) {
    const maybeFsError = error as NodeJS.ErrnoException;
    if (maybeFsError.code === "ENOENT") {
      return [];
    }
    throw error;
  }
}

export function releasedDocsVersionsPlugin(): Plugin {
  return {
    name: "released-docs-versions",
    resolveId(id) {
      if (id === VIRTUAL_ID) return RESOLVED_ID;
    },
    load(id) {
      if (id !== RESOLVED_ID) return;
      const folders = getReleasedDocsVersions();
      return `export const RAW_RELEASED_DOC_VERSIONS = ${JSON.stringify(folders)};`;
    }
  };
}
