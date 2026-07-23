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
import {
  access,
  cp,
  mkdir,
  readFile,
  readdir,
  rm,
  writeFile
} from "node:fs/promises";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { CURRENT_VERSION } from "../app/lib/current-version";
import {
  DOCS_ARCHIVE_BASE_ENV,
  normalizeDocsArchiveBase
} from "../app/lib/docs-archive";
import { formatDocsBase } from "../app/lib/docs-paths";

const ROOT = join(import.meta.dirname, "..");
const BUILD_DIR = join(ROOT, "build");
const BUILD_CLIENT_DIR = join(BUILD_DIR, "client");
const DOCS_OUTPUT_ROOT = join(BUILD_DIR, "doc");
const DOCS_MDX_DIR = join(ROOT, "app", "pages", "_docs", "docs", "_mdx");
const REQUIRED_DOCS_PATHS = [
  "index.html",
  "api/search",
  "llms-full.txt",
  ".htaccess"
];
const ROOT_RELATIVE_DOC_SECTIONS = "overview|developer|admin-ops|miscellaneous";

export const ROOT_URL_PATTERNS = [
  new RegExp(
    `(?:href|src)=["']\\/(?:${ROOT_RELATIVE_DOC_SECTIONS}|assets|docs-images|api\\/search|apidocs|fonts|images|favicon\\.ico)\\b`,
    "g"
  ),
  /fetch\(["']\/api\/search["']/g,
  new RegExp(
    `url\\(["']?\\/(?:${ROOT_RELATIVE_DOC_SECTIONS}|assets|docs-images|api\\/search|apidocs|fonts|images|favicon\\.ico)\\b`,
    "g"
  )
];

// Vite rewrites imported assets, but archive-local public URLs and Fumadocs
// external links can remain literal root paths in prerendered HTML.
const DOCS_LOCAL_PATHS =
  "(?:apidocs|assets|docs-images|fonts|images)\\b[^\"')]*|favicon\\.ico";

export function parseDocsBuildArgs(args = process.argv.slice(2)): void {
  if (args.length > 0) {
    throw new Error("Usage: npm run build:docs");
  }
}

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

async function pathExists(path: string): Promise<boolean> {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
}

async function collectFiles(directory: string): Promise<string[]> {
  const files: string[] = [];

  async function visit(current: string) {
    const entries = await readdir(current, { withFileTypes: true });
    for (const entry of entries) {
      const absolutePath = join(current, entry.name);
      if (entry.isDirectory()) {
        await visit(absolutePath);
      } else if (entry.isFile()) {
        files.push(relative(directory, absolutePath).replaceAll("\\", "/"));
      }
    }
  }

  await visit(directory);
  return files.sort();
}

export function toArchiveHtmlPathFromMdx(relativePath: string): string {
  const routePath = relativePath
    .replaceAll("\\", "/")
    .replace(/\.mdx$/, "")
    .replace(/(?:^|\/)index$/, "");

  return routePath ? `${routePath}/index.html` : "index.html";
}

export async function collectExpectedDocsArchivePaths(
  docsMdxDir = DOCS_MDX_DIR
): Promise<string[]> {
  return (await collectFiles(docsMdxDir))
    .filter((file) => file.endsWith(".mdx"))
    .map(toArchiveHtmlPathFromMdx)
    .sort();
}

async function copyDocsOutput(version: string): Promise<string> {
  const outputDir = join(DOCS_OUTPUT_ROOT, `r${version}`);
  const nestedBuildDir = join(BUILD_CLIENT_DIR, "doc", `r${version}`);
  const sourceDir = (await pathExists(nestedBuildDir))
    ? nestedBuildDir
    : BUILD_CLIENT_DIR;

  await rm(outputDir, { recursive: true, force: true });
  await mkdir(outputDir, { recursive: true });
  await cp(sourceDir, outputDir, { recursive: true });

  if (sourceDir === nestedBuildDir) {
    for (const path of ["assets", "docs-images", "fonts", "images"]) {
      const sourcePath = join(BUILD_CLIENT_DIR, path);
      if (await pathExists(sourcePath)) {
        await cp(sourcePath, join(outputDir, path), { recursive: true });
      }
    }

    for (const path of ["favicon.ico", "docs.data"]) {
      const sourcePath = join(BUILD_CLIENT_DIR, path);
      if (await pathExists(sourcePath)) {
        await cp(sourcePath, join(outputDir, path));
      }
    }
  }

  return outputDir;
}

export function createArchiveHtaccessContent(): string {
  return `# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

RewriteEngine On

RewriteCond %{REQUEST_FILENAME} !-f
RewriteCond %{REQUEST_FILENAME} !-d
RewriteRule ^(.*)$ /$1 [R=302,L]
`;
}

async function writeArchiveHtaccess(outputDir: string) {
  await writeFile(join(outputDir, ".htaccess"), createArchiveHtaccessContent());
}

async function rewriteDocsLocalUrls(outputDir: string, docsBase: string) {
  const files = (await collectFiles(outputDir)).filter((file) =>
    /\.(?:html|css)$/.test(file)
  );
  const attributePattern = new RegExp(
    `((?:href|src)=)(["'])\\/(${DOCS_LOCAL_PATHS})\\2`,
    "g"
  );
  const cssUrlPattern = new RegExp(
    `(url\\()(["']?)\\/(${DOCS_LOCAL_PATHS})\\2(\\))`,
    "g"
  );

  for (const file of files) {
    const filePath = join(outputDir, file);
    const content = await readFile(filePath, "utf8");
    const rewritten = content
      .replace(attributePattern, `$1$2${docsBase}$3$2`)
      .replace(cssUrlPattern, `$1$2${docsBase}$3$2$4`);

    if (rewritten !== content) {
      await writeFile(filePath, rewritten);
    }
  }
}

export async function verifyArchiveOutput(
  outputDir: string,
  docsMdxDir = DOCS_MDX_DIR
) {
  const files = await collectFiles(outputDir);
  const fileSet = new Set(files);

  for (const path of REQUIRED_DOCS_PATHS) {
    if (!fileSet.has(path)) {
      throw new Error(`Docs build is missing ${path}`);
    }
  }

  if (
    !files.some((file) => file !== "index.html" && file.endsWith("/index.html"))
  ) {
    throw new Error("Docs build is missing nested docs pages");
  }

  for (const path of await collectExpectedDocsArchivePaths(docsMdxDir)) {
    if (!fileSet.has(path)) {
      throw new Error(`Docs build is missing docs page ${path}`);
    }
  }

  const textFiles = files.filter((file) => /\.(?:html|css)$/.test(file));

  for (const file of textFiles) {
    const content = await readFile(join(outputDir, file), "utf8");
    const matchedPattern = ROOT_URL_PATTERNS.find((pattern) => {
      pattern.lastIndex = 0;
      return pattern.test(content);
    });

    if (matchedPattern) {
      throw new Error(
        `Docs build contains a root-relative docs URL in ${file}: ${matchedPattern}`
      );
    }
  }

  console.log(`Verified docs build at ${outputDir} (${files.length} files).`);
}

// Pure, self-contained docs build for a single version. Produces a versioned
// output at build/doc/r<version>/ with its own assets, .htaccess, rewritten
// docs-local URLs, and llms-full.txt.
export async function buildDocs(version: string): Promise<string> {
  const docsBase = normalizeDocsArchiveBase(formatDocsBase(version));
  const env = {
    ...process.env,
    [DOCS_ARCHIVE_BASE_ENV]: docsBase
  };

  console.log(`Building docs for ${docsBase}`);
  runCommand("npx", ["react-router", "build"], env);

  const outputDir = await copyDocsOutput(version);
  await writeArchiveHtaccess(outputDir);
  await rewriteDocsLocalUrls(outputDir, docsBase);
  await verifyArchiveOutput(outputDir);

  return outputDir;
}

export async function main() {
  parseDocsBuildArgs();

  await buildDocs(CURRENT_VERSION);

  // react-router build emits to build/client/, then buildDocs() assembles the
  // self-contained deliverable at build/doc/r<v>/. The leftover build/client/
  // is just intermediate output — drop it so build/doc/ is the only root.
  await rm(BUILD_CLIENT_DIR, { recursive: true, force: true });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await main();
}
