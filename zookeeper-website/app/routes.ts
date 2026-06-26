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

import {
  type RouteConfig,
  index,
  layout,
  route
} from "@react-router/dev/routes";
import { CURRENT_VERSION } from "./lib/current-version";
import { getBuildTarget, getDocsArchiveBase } from "./lib/docs-archive";

// Three build contexts share one codebase:
//   - Docs build (archive base set): docs are served under /doc/rX/ via Vite
//     `base` + React Router `basename`. Includes search + llms-full.txt. The
//     current version is built exactly like an archive.
//   - Landing build (ZOOKEEPER_BUILD_TARGET=landing): landing pages + the /doc
//     redirect only, so docs changes never touch landing output.
//   - Dev / default (neither): a combined site so `react-router dev` serves
//     landing and current docs together for authoring.
const isDocsArchiveBuild = Boolean(getDocsArchiveBase());
const isLandingBuild = getBuildTarget() === "landing";

const landingRoutes = layout("./pages/_landing/landing-layout.tsx", [
  index("routes/_landing/home.tsx"),
  route("releases", "routes/_landing/releases.tsx"),
  route("events", "routes/_landing/events.tsx"),
  route("news", "routes/_landing/news.tsx"),
  route("credits", "routes/_landing/credits.tsx"),
  route("bylaws", "routes/_landing/bylaws.tsx"),
  route("mailing-lists", "routes/_landing/mailing-lists.tsx"),
  route("security", "routes/_landing/security.tsx"),
  route("slack", "routes/_landing/slack.tsx"),
  route("version-control", "routes/_landing/version-control.tsx")
]);

const docRedirectRoute = route("doc", "routes/_docs/doc-redirect.tsx");

const docsBuildRoutes = [
  layout("./pages/_docs/docs-layout.tsx", [
    index("routes/_docs/doc.tsx", { id: "docs-index" }),
    route("*", "routes/_docs/doc.tsx", { id: "docs-splat" })
  ]),
  route("api/search", "routes/_api/search.ts"),
  route("llms-full.txt", "routes/_api/llms-full.ts")
] satisfies RouteConfig;

const landingBuildRoutes = [
  landingRoutes,
  docRedirectRoute
] satisfies RouteConfig;

const devCombinedRoutes = [
  landingRoutes,
  docRedirectRoute,
  layout("./pages/_docs/docs-layout.tsx", [
    route(`doc/r${CURRENT_VERSION}/*`, "routes/_docs/doc.tsx")
  ]),
  route("api/search", "routes/_api/search.ts"),
  route("llms-full.txt", "routes/_api/llms-full.ts")
] satisfies RouteConfig;

const routes = isDocsArchiveBuild
  ? docsBuildRoutes
  : isLandingBuild
    ? landingBuildRoutes
    : devCombinedRoutes;

export default routes;
