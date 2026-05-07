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

import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import tsconfigPaths from "vite-tsconfig-paths";
import { resolve } from "path";
import mdx from "fumadocs-mdx/vite";
import * as MdxConfig from "./source.config";
import { releasedDocsVersionsPlugin } from "./plugins/released-docs-versions";
import { CURRENT_VERSION } from "./app/lib/current-version";

export default defineConfig({
  plugins: [
    releasedDocsVersionsPlugin(),
    mdx(MdxConfig),
    react(),
    tsconfigPaths()
  ],
  define: {
    __CURRENT_VERSION__: JSON.stringify(CURRENT_VERSION)
  },
  test: {
    globals: true,
    environment: "happy-dom",
    setupFiles: ["./unit-tests/setup.ts"],
    css: true,
    pool: "threads",
    exclude: [
      "**/node_modules/**",
      "**/dist/**",
      "**/e2e-tests/**",
      "**/playwright-report/**",
      "**/test-results/**"
    ]
  },
  resolve: {
    alias: {
      "@/.source": resolve(__dirname, ".source"),
      "@": resolve(__dirname, "app")
    }
  }
});
