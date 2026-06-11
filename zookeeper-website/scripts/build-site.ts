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

import { spawnSync } from "node:child_process";
import { cp, mkdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { CURRENT_VERSION } from "../app/lib/current-version";
import { DOCS_BUILD_TARGET_ENV } from "../app/lib/docs-archive";
import { buildDocs } from "./build-docs";

const ROOT = join(import.meta.dirname, "..");
const BUILD_CLIENT_DIR = join(ROOT, "build", "client");

function runCommand(command: string, args: string[], env: NodeJS.ProcessEnv) {
  const result = spawnSync(command, args, {
    cwd: ROOT,
    env,
    stdio: "inherit"
  });

  if (result.error) {
    throw result.error;
  }

  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(" ")} failed`);
  }
}

// Builds the live site as two independent Vite builds that get merged for local
// serving (vite preview / e2e) and for publishing:
//   1. Current docs (base /doc/r<CURRENT>/) -> build/doc/r<CURRENT>/. Built first
//      so it also produces the .source files the landing build relies on.
//   2. Stash the docs output outside build/, because react-router build wipes the
//      whole build/ directory at the start of every build.
//   3. Landing (base /, ZOOKEEPER_BUILD_TARGET=landing) -> build/client/ (root).
//   4. Restore the docs output into build/client/doc/r<CURRENT>/.
//   5. Generate the sitemap over the merged output (landing + current docs).
export async function main() {
  const docsOutputDir = await buildDocs(CURRENT_VERSION);

  const stashDir = join(tmpdir(), `zk-current-docs-r${CURRENT_VERSION}`);
  await rm(stashDir, { recursive: true, force: true });
  await cp(docsOutputDir, stashDir, { recursive: true });

  console.log("Building landing site");
  runCommand("npx", ["react-router", "build"], {
    ...process.env,
    [DOCS_BUILD_TARGET_ENV]: "landing"
  });

  const mergedDocsDir = join(BUILD_CLIENT_DIR, "doc", `r${CURRENT_VERSION}`);
  await rm(mergedDocsDir, { recursive: true, force: true });
  await mkdir(mergedDocsDir, { recursive: true });
  await cp(stashDir, mergedDocsDir, { recursive: true });
  await rm(stashDir, { recursive: true, force: true });
  console.log(`Merged current docs into ${mergedDocsDir}`);

  runCommand(
    "npm",
    ["run", "generate-sitemap", "--", "--scope", "all"],
    process.env
  );
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await main();
}
