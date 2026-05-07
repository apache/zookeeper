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

import { reactRouter } from "@react-router/dev/vite";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig, createLogger } from "vite";
import tsconfigPaths from "vite-tsconfig-paths";
import { resolve } from "path";
import mdx from "fumadocs-mdx/vite";
import * as MdxConfig from "./source.config";
import { releasedDocsVersionsPlugin } from "./plugins/released-docs-versions";
import { CURRENT_VERSION } from "./app/lib/current-version";

// Create custom logger to filter out benign warnings
const logger = createLogger();
const originalWarn = logger.warn;
logger.warn = (msg, options) => {
  // Suppress public directory warnings - these are informational only
  if (msg.includes("Files in the public directory are served at the root path"))
    return;
  if (
    msg.includes(
      "Assets in public directory cannot be imported from JavaScript"
    )
  )
    return;
  // Suppress Babel deoptimization warnings for large MDX files
  if (msg.includes("deoptimised the styling")) return;
  // Log all other warnings
  originalWarn(msg, options);
};

export default defineConfig({
  plugins: [
    releasedDocsVersionsPlugin(),
    mdx(MdxConfig),
    tailwindcss(),
    reactRouter(),
    tsconfigPaths()
  ],
  resolve: {
    alias: {
      "@/.source": resolve(__dirname, ".source"),
      "@": resolve(__dirname, "app")
    }
  },
  define: {
    __CURRENT_VERSION__: JSON.stringify(CURRENT_VERSION)
  },
  customLogger: logger
});
