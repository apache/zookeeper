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

import type { Config } from "@react-router/dev/config";
import { glob } from "node:fs/promises";
import { createGetUrl, getSlugs } from "fumadocs-core/source";
import { getBuildTarget, getDocsArchiveBase } from "./app/lib/docs-archive";
import { CURRENT_VERSION } from "./app/lib/current-version";
import { formatDocsBase } from "./app/lib/docs-paths";

const docsArchiveBase = getDocsArchiveBase();
const isLandingBuild = getBuildTarget() === "landing";
const getUrl = createGetUrl(
  docsArchiveBase ? "/" : formatDocsBase(CURRENT_VERSION)
);

export default {
  // Config options...
  // Server-side render by default, to enable SPA mode set this to `false`
  ssr: false,
  basename: docsArchiveBase || "/",
  future: {
    v8_middleware: false,
    v8_splitRouteModules: false,
    v8_viteEnvironmentApi: false,
    v8_passThroughRequests: false,
    v8_trailingSlashAwareDataRequests: false
  },
  async prerender({ getStaticPaths }) {
    const paths: string[] = [...getStaticPaths()];
    // The landing build has no docs routes, so skip enumerating MDX pages.
    if (!isLandingBuild) {
      for await (const entry of glob("**/*.mdx", {
        cwd: "app/pages/_docs/docs/_mdx"
      })) {
        paths.push(getUrl(getSlugs(entry)));
      }
    }
    return paths;
  }
} satisfies Config;
