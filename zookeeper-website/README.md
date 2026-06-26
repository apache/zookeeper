<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Apache ZooKeeper Website

The official website for Apache ZooKeeper, built with modern web technologies to provide a fast, accessible, and maintainable web presence.

---

## Table of Contents

- [Content Editing](#content-editing)
- [Development](#development)
  - [Prerequisites](#prerequisites)
  - [Technology Stack](#technology-stack)
  - [Project Architecture](#project-architecture)
  - [Getting Started](#getting-started)
  - [Development Workflow](#development-workflow)
  - [Testing](#testing)
  - [Building for Production](#building-for-production)
    - [How the Docs Build Works](#how-the-docs-build-works)
    - [Continuous Integration](#continuous-integration)
  - [Maven Integration](#maven-integration)
  - [Deployment](#deployment)
  - [Publishing a New ZooKeeper Release](#publishing-a-new-zookeeper-release)
  - [Troubleshooting](#troubleshooting)

---

## Content Editing

Most landing pages store content in **Markdown (`.md`)**, and some in **JSON (`.json`)**, files located in `app/pages/_landing/[page-name]/`. Docs content lives under `app/pages/_docs/` and content is stored in `.mdx` (it's just extended markdown).

**Examples:**

- `app/pages/_landing/releases/index.tsx` - Releases page
- `app/pages/_docs/docs/_mdx/...` - MDX content for documentation

The credits page (`app/pages/_landing/credits/`) is a special case: its developer list is **generated** from the project's parent [`pom.xml`](../pom.xml) `<developers>` section by [`scripts/extract-developers.js`](scripts/extract-developers.js). The generated `developers.json` is gitignored and produced as the first step of both `npm run ci` and `npm run ci-skip-tests`. To edit who appears on `/credits`, update the `<developers>` block in the parent `pom.xml`.

---

## Development

### Prerequisites

Before you begin, ensure you have the following installed:

- **Node.js — 22, 23, or 24** (JavaScript runtime; like the JVM for Java)
  - Download from [nodejs.org](https://nodejs.org/)
  - Verify installation: `node --version` (must satisfy `^22.12.0 || ^23.0.0 || ^24.0.0`; see [`zookeeper-website/package.json`](package.json) `engines.node`)
  - **Node 25 and 26 are not supported** — some tests fail under those releases. The `engines` field in `package.json` advertises the allowed range; npm prints a warning when it does not match but still installs. Treat the warning as blocking and downgrade to a supported Node line before running the CI pipeline.

- **NPM** - Node Package Manager (like Maven for Java)
  - Comes bundled with Node.js
  - Verify installation: `npm --version`

### Technology Stack

This website uses modern web technologies. Here's what each one does (with Java analogies):

#### Core Framework

- **React Router** - Full-stack web framework with Server-Side Generation (SSG)
  - Handles routing (like Spring MVC controllers)
  - Provides server-side rendering for better performance and SEO
  - Enables progressive enhancement (see below)
  - [Documentation](https://reactrouter.com/)

#### Documentation Framework

- **Fumadocs** - Documentation framework used for the docs section
  - Provides MDX-based docs structure and navigation
  - Lives alongside the landing pages in the same React Router app
  - [Documentation](https://fumadocs.com/)

#### Progressive Enhancement

The website uses **progressive enhancement** ([learn more](https://reactrouter.com/explanation/progressive-enhancement)), which means:

- **With JavaScript enabled**: Users get a Single Page Application (SPA) experience
  - Fast page transitions without full page reloads
  - Smooth animations and interactive features
  - Enhanced user experience

- **Without JavaScript**: Core content and navigation remain accessible via server-rendered HTML
  - Primary page content and most links work without client-side routing
  - Critical flows such as the "Older docs" menu include no-JS fallbacks
  - Some features (search, theme toggle, interactive menus) don't work since they require JavaScript

This approach keeps the site usable without JavaScript while still providing a richer experience when it is available.

#### UI Components

- **shadcn/ui** - Pre-built, accessible UI components
  - Similar to a component library like PrimeFaces or Vaadin in Java
  - Provides buttons, cards, navigation menus, etc.
  - [Documentation](https://ui.shadcn.com/)

#### Styling

- **TailwindCSS** - Utility-first CSS framework, aka Bootstrap on steroids
  - Instead of writing CSS files, you apply classes directly in components
  - Example: `className="text-blue-500 font-bold"` makes blue, bold text

#### Code Quality Tools

- **TypeScript** - Typed superset of JavaScript
  - Similar to Java's type system
  - Catches errors at compile-time instead of runtime
  - Provides autocomplete and better IDE support

- **ESLint + Prettier** - Code linting and formatting (like Checkstyle)
  - ESLint analyzes code for potential errors and enforces coding standards
  - Prettier handles automatic code formatting (spacing, indentation, etc.)
  - Integrated together: `npm run lint:fix` handles both linting and formatting
  - Configuration: `eslint.config.js` and `prettier.config.js`

### Project Architecture

The project follows a clear directory structure with separation of concerns:

```
zookeeper-website/
├── app/                               # Application source code
│   ├── ui/                            # Reusable UI components (no business logic)
│   │   ├── button.tsx                 # Generic button component
│   │   ├── card.tsx                   # Card container component
│   │   └── ...                        # Other UI primitives
│   │
│   ├── components/                    # Reusable components WITH business logic
│   │   ├── site-navbar.tsx            # Website navigation bar
│   │   ├── site-footer.tsx            # Website footer
│   │   ├── theme-toggle.tsx           # Dark/light mode toggle
│   │
│   ├── pages/                         # Complete pages (composed of ui + components)
│   │   ├── _landing/                  # Landing pages + layout
│   │   │   ├── home/                  # Home page
│   │   │   │   ├── index.tsx          # Main page component (exported)
│   │   │   │   ├── hero.tsx           # Hero section (not exported)
│   │   │   │   ├── features.tsx       # Features section (not exported)
│   │   │   │   └── ...
│   │   │   ├── news/                  # Landing page content
│   │   │   └── ...
│   │   ├── _docs/                     # Documentation (Fumadocs)
│   │   │   ├── docs/                  # MDX content and structure
│   │   │   ├── docs-layout.tsx        # Fumadocs layout wrapper
│   │   │   └── ...
│   │
│   ├── routes/                        # Route definitions and metadata
│   │   ├── _landing/                  # Landing page routes
│   │   │   ├── home.tsx               # Home route configuration
│   │   │   ├── news.tsx               # News route configuration
│   │   │   └── ...
│   │   └── _docs/                     # Docs route configuration
│   │
│   ├── lib/                           # Utility functions and integrations
│   │   ├── utils.ts                   # Helper functions
│   │   └── theme-provider.tsx         # Theme management
│   │
│   ├── routes.ts                      # Main routing configuration
│   ├── root.tsx                       # Root layout component
│   └── app.css                        # Global styles
│
├── build/                        # Generated files (DO NOT EDIT)
│   ├── client/                   # Browser-side assets
│   │   ├── index.html            # HTML files for each page
│   │   ├── assets/               # JavaScript, CSS bundles
│   │   └── images/               # Optimized images
│
├── public/                       # Static files (copied as-is to build/)
│   ├── favicon.ico               # Website icon
│   ├── images/                   # Image assets
│   ├── docs-images/              # Docs image assets
│   └── ...
│
├── node_modules/                 # Dependencies (like Maven's .m2 directory)
├── package.json                  # Project metadata and dependencies (like pom.xml)
├── tsconfig.json                 # TypeScript configuration
└── react-router.config.ts        # React Router framework configuration
```

#### Key Principles

1. **UI Components (`/ui`)**: Pure, reusable components with no business logic
   - Can be used anywhere in the application
   - Only concerned with appearance and basic interaction

2. **Business Components (`/components`)**: Reusable across pages
   - May contain business logic specific to ZooKeeper website
   - Examples: navigation, footer, theme toggle

3. **Pages (`/pages`)**: Complete pages combining ui and components
   - Each page has its own directory
   - Only `index.tsx` is exported
   - Internal components stay within the page directory
   - If a component needs to be reused, move it to `/components`

4. **Routes (`/routes`)**: Define routing and metadata
   - Maps URLs to pages
   - Sets page titles, meta tags, etc.

5. **One Codebase, Two Independent Builds**:
   - **Landing pages** live under `app/pages/_landing/` and use the landing layout; they are served from the site root (`/`).
   - **Docs pages** live under `app/pages/_docs/` and use Fumadocs layouts; they are served from a versioned base (`/doc/r<version>/`).
   - They share one codebase but are produced as two **separate** builds (landing at base `/`, docs at base `/doc/r<version>/`). A docs change therefore never rewrites landing assets, and vice versa, keeping `asf-site` diffs clean. `npm run build` runs both and merges them; `npm run dev` serves a combined site so local authoring is unaffected.
   - The build context is selected by environment variables read in [`app/routes.ts`](app/routes.ts) and the Vite/React Router configs:
     - `ZOOKEEPER_DOCS_ARCHIVE_BASE=/doc/rX/` → **docs build** (any version, including the current one): docs catch-all + `api/search` + `llms-full.txt`, served under `/doc/rX/`.
     - `ZOOKEEPER_BUILD_TARGET=landing` → **landing build**: landing pages + the `/doc` redirect only.
     - neither (default, e.g. `react-router dev`) → **combined** site for local development.

6. **Documentation Structure**:
   - **Docs** live under `app/pages/_docs/docs/_mdx/` and are the source of truth.

7. **Documentation Versions**:

   Three constants drive the versions surfaced by the site, all defined together for a reason:
   - **`CURRENT_VERSION`** ([`app/lib/current-version.ts`](app/lib/current-version.ts)) — the version the in-tree MDX corresponds to. On `master` this is the **canary** version that the next release is being prepared for. The docs build serves it from `/doc/r<CURRENT_VERSION>/`.
   - **`RAW_RELEASED_DOC_VERSIONS_LIST`** (private literal in [`app/lib/released-docs-versions.ts`](app/lib/released-docs-versions.ts), exported as the Set `RAW_RELEASED_DOC_VERSIONS`) — every documentation version that has ever been published under `/doc/`. New entries are appended when a release ships, and the list is kept in sync with the `content/doc/r<version>/` directories on `asf-site`. `CURRENT_VERSION` is included at the end so it is exposed through the same machinery.
   - **`LTS_VERSIONS`** ([`app/lib/released-docs-versions.ts`](app/lib/released-docs-versions.ts)) — versions that are pinned to the top of the **Documentation** dropdown in the site navbar. By convention `CURRENT_VERSION` is **first** in this list, followed by the still-supported long-term branches. Everything else from `RAW_RELEASED_DOC_VERSIONS_LIST` is shown afterwards under "Older versions", sorted in descending order.

   When releasing a new version, both `RAW_RELEASED_DOC_VERSIONS_LIST` and `LTS_VERSIONS` typically need to be touched — see the [release flow](#publishing-a-new-zookeeper-release). For routine non-release edits, neither file changes.

#### Important Conventions

##### Custom Link Component

**Always use the custom Link component from `@/components/link` instead of importing Link directly from `react-router`.**

The ZooKeeper website includes pages that are not part of this React Router application (e.g., documentation pages, API docs). The custom Link component automatically determines whether a link should trigger a hard reload or use React Router's client-side navigation:

**Usage:**

```typescript
// ✅ CORRECT - Use custom Link component
import { Link } from "@/components/link";

export const MyComponent = () => (
  <Link to="/news">News</Link>
);
```

```typescript
// ❌ WRONG - Do not import Link from react-router
import { Link } from "react-router";

export const MyComponent = () => (
  <Link to="/news">News</Link>
);
```

The ESLint configuration includes a custom rule (`custom/no-react-router-link`) that will throw an error if you attempt to import `Link` from `react-router`, helping enforce this convention automatically.

### Getting Started

#### 1. Install Dependencies

Think of this as `mvn install`:

```bash
npm ci
```

`npm ci` (short for "clean install") downloads all required packages from npm (similar to Maven Central). **Prefer `ci` over `npm install` for any reproducible build** — your local dev tree, contributor checkouts, the release publish flow, CI:

- It installs the **exact** versions pinned in `package-lock.json`. `npm install` is allowed to upgrade dependencies within the semver ranges in `package.json` and to rewrite the lock file; `npm ci` never touches the lock file.
- It wipes `node_modules/` first, so the resulting tree always matches the lock file rather than carrying over stale packages from a previous install.
- It is also typically faster than `npm install` in a fresh checkout.

#### 2. Start Development Server

```bash
npm run dev
```

This starts a local development server with:

- **Hot Module Replacement (HMR)**: Code changes appear instantly without full page reload
- **Live at**: `http://localhost:5173`

### Development Workflow

#### Making Changes

1. **Edit code** in the `app/` directory
2. **Save the file** - changes appear automatically in the browser
3. **Check for errors** in the terminal where `npm run dev` is running and in browser console

#### Common Tasks

**Add a new landing page:**

1. Create a directory in `app/pages/_landing/my-new-page/`
2. Create `index.tsx` in that directory and export the page component
3. Create a route file in `app/routes/_landing/my-new-page.tsx` with `meta()` and a default export
4. Register the route in `app/routes.ts` inside the `_landing` layout block

**Add a new documentation page:**

1. Create a new `.mdx` file in `app/pages/_docs/docs/_mdx/` (for example `my-topic.mdx`).
2. Add the new file to the relevant `meta.json` in the same section folder so it appears in navigation.

When writing docs content, follow these conventions:

- Each MDX file starts with a YAML frontmatter block declaring at minimum `title` and `description`. These power the page `<title>`, the sidebar label, and the meta description:

  ```mdx
  ---
  title: "My topic"
  description: "Short one-liner."
  ---
  ```

- Internal doc links are **doc-root-relative** — write `/admin-ops/cli`, not `/docs/...` and not a hardcoded `/doc/r<version>/...`. The version prefix (`/doc/r<version>`) is added automatically at render time (`resolveDocsHref` on the live site, React Router `basename` in an archive).
- Images use the static asset path `/docs-images/...` (served from `public/docs-images/`).
- Use absolute `https://` URLs for off-site links (e.g. `https://zookeeper.apache.org/`).

**Update content:**

- Edit the appropriate `.md`, `.mdx`, or `.json` file
- Changes appear automatically

**Add a UI component:**

- Check if shadcn/ui has what you need first
- Only create custom components if necessary

**Update the 404 page:**

There are two separate 404 mechanisms:

- **Static Apache 404** (`public/404.html`): Served by Apache for requests that never reach the SPA (missing static files, direct URL hits). Configure handling in `public/.htaccess` (`ErrorDocument 404 /404.html`). Keep `public/robots.txt` disallowing `/404.html` so the error page is not indexed.
- **In-app error boundary** (`app/root.tsx`): Handles unknown routes after the React app loads (for example, invalid docs paths). Edit `ErrorBoundary` in `root.tsx` to change that experience.

**Check code quality:**

```bash
npm run lint
```

**Fix linting and formatting issues:**

```bash
npm run lint:fix
```

### Testing

The project uses [Vitest](https://vitest.dev/) and [Playwright](http://playwright.dev/) for testing. Vitest is for unit testing, while Playwright is for e2e testing.

**Note:** Playwright tests are configured to run against the production build. Therefore, you must build the project (`npm run build`) before running the e2e tests locally.

**Run tests:**

```bash
# Run all tests (unit + e2e)
# Note: e2e tests require a production build first (`npm run build`)
npm test

# Run unit tests once (for CI/CD)
npm run test:unit:run

# Run unit tests with UI
npm run test:unit:ui

# Run e2e tests (requires `npm run build` first)
npm run test:e2e

# Run e2e tests with UI (requires `npm run build` first)
npm run test:e2e:ui
```

**Writing new tests:**

Use the `renderWithProviders` utility in `unit-tests/utils.tsx` to ensure components have access to routing and theme context:

```typescript
import { renderWithProviders, screen } from "./utils";
import { MyComponent } from "@/components/my-component";

describe("MyComponent", () => {
  it("renders correctly", () => {
    renderWithProviders(<MyComponent />);
    expect(screen.getByText("Hello World")).toBeInTheDocument();
  });
});
```

### Building for Production

**CI/CD Workflow:**

Before merging or deploying, run the full CI pipeline:

```bash
npm run ci
```

This command runs all quality checks and builds the project. All checks must pass before code is considered ready.

If you need the CI flow without unit/e2e test suites, use:

```bash
npm run ci-skip-tests
```

This runs docs initialization and the production build (without lint/typecheck and without unit/e2e test suites).

Generated files are located under the `build/` directory.

**Build commands:**

The landing site and the docs are produced as two independent Vite builds (see [One Codebase, Two Independent Builds](#key-principles)). `npm run build` orchestrates them via [`scripts/build-site.ts`](scripts/build-site.ts):

- `npm run build` — builds the current docs (`/doc/r<CURRENT_VERSION>/`), then the landing site (base `/`), then merges the docs into `build/client/doc/r<CURRENT_VERSION>/` and regenerates the full sitemap (`--scope all`). The merged `build/client/` is what `npm run start`, `vite preview`, and the e2e tests serve.
- `npm run build:landing` — landing-only build (sets `ZOOKEEPER_BUILD_TARGET=landing`), output at `build/client/` (root pages).
- `npm run build:docs` — pure docs build for `CURRENT_VERSION`, output at `build/doc/r<CURRENT_VERSION>/`. No CI checks.

Two code-generation steps feed the build:

- **`npm run extract-developers`** writes `app/pages/_landing/credits/developers.json` from the parent [`pom.xml`](../pom.xml). It is invoked automatically by any landing-site build: `build:landing` runs it before Vite, and `build` runs it inside [`scripts/build-site.ts`](scripts/build-site.ts) before the landing step. `build:docs` does not need it (no credits route in the docs build).
- **`npm run fumadocs-init`** writes the fumadocs MDX index to `.source/`. Vite's `fumadocs-mdx/vite` plugin regenerates `.source/` at build/test time, so this step is **not** needed by any build commands. It **is** needed by `lint`, because eslint's `import/no-unresolved` resolver reads the filesystem directly and will fail on the two `@/.source` imports if `.source/` does not exist.

The docs version always comes from [`app/lib/current-version.ts`](app/lib/current-version.ts).

`CURRENT_VERSION` on the **`master`** branch is **not** a released version — it is the **canary** for the next release that is being worked on. The actual released version is whatever sits at `content/doc/current/` on `asf-site`. `CURRENT_VERSION` is bumped on `master` as soon as work on the next version begins (see [Publishing a New ZooKeeper Release](#publishing-a-new-zookeeper-release)); from that point on, edits to docs on `master` accumulate under the new canary value and ship together when that release cuts.

Because each docs version and the landing site are separate Vite builds, they have independent asset hashes: editing docs never changes landing output, and vice versa. This is what makes it possible to **ship landing-page updates and docs updates independently**:

- A typo fix or new section in the landing pages can be published to `asf-site` without rebuilding or re-publishing the current docs directory.
- A docs-only change (a fix in MDX content, or a new archived docs version) can be published without touching the landing files.
- The [basic workflow](#basic-workflow) below describes the combined rebuild.

> Note: React Router cleans the entire `build/` directory at the start of every build, so the orchestrator stashes the docs output outside `build/` before running the landing build, then restores it into `build/client/`.

#### How the Docs Build Works

`npm run build:docs` produces a self-contained docs tree at `build/doc/r<CURRENT_VERSION>/`, ready to copy to `asf-site` at `content/doc/r<CURRENT_VERSION>/`. It validates the output before exiting — every source MDX docs page must have a corresponding HTML page.

The docs build uses React Router's `basename` and Vite's `base` to serve docs from `/doc/r<version>/`. URL shape:

- `/doc/r3.9.6/`
- `/doc/r3.9.6/overview/quick-start`
- `/doc/r3.9.6/developer/programmers-guide`

It uses the docs route set from `app/routes.ts`: the docs catch-all, `api/search`, and `llms-full.txt`. Non-doc archive-local requests such as `/doc/r3.9.6/news` are redirected by the generated `.htaccess` file to the matching live-site path (`/news`).

Docs builds set the `ZOOKEEPER_DOCS_ARCHIVE_BASE` env var. Vite turns it into the app `base`, which the browser reads back as `import.meta.env.BASE_URL`. Rule of thumb for the build context:

- Node-side code (vite/react-router configs, scripts) reads `ZOOKEEPER_DOCS_ARCHIVE_BASE` and `ZOOKEEPER_BUILD_TARGET` from `process.env` (via `getDocsArchiveBase()` / `getBuildTarget()` in [`app/lib/docs-archive.ts`](app/lib/docs-archive.ts)).
- Bundled app code reads the docs base back as `import.meta.env.BASE_URL`.

`ZOOKEEPER_DOCS_ARCHIVE_BASE` and `import.meta.env.BASE_URL` are the same value in two execution contexts. `ZOOKEEPER_BUILD_TARGET=landing` is the orthogonal signal that selects the landing-only build.

The docs build script packages the generated docs into a self-contained directory. React Router prerenders docs HTML under `build/client/doc/r<version>/`, while Vite and `public/` assets are emitted at the build root (`build/client/assets/`, `build/client/docs-images/`, `build/client/fonts/`, `build/client/images/`, `build/client/favicon.ico`). The script copies the docs HTML plus those known static assets into `build/doc/r<version>/` so URLs such as `/doc/r3.9.6/assets/...` exist after publishing, then deletes the intermediate `build/client/` so `build/doc/r<version>/` is the only output. If new top-level static folders or asset roots are introduced, update `scripts/build-docs.ts` so they are copied, URL-rewritten if needed, and covered by docs build validation.

Docs output intentionally does not copy the live site's root-only files such as `404.html`, `robots.txt`, `__spa-fallback.html`, or the root `.htaccess`. Docs builds get their own generated `.htaccess` (described above). The docs build keeps only docs routes, `api/search`, and `llms-full.txt`.

#### Continuous Integration

GitHub Actions runs the website build on every push and pull request via [`.github/workflows/website.yaml`](../.github/workflows/website.yaml). The workflow:

1. Sets up JDK 11 and Node.js 22
2. Runs `npm ci` and installs Playwright browsers with system dependencies
3. Executes `mvn -pl zookeeper-website site` (same checks as local `npm run ci`)

Pull requests should pass the **Website / website-site** check before merge.

### Maven Integration

The website is integrated into the Apache ZooKeeper Maven build through two POMs:

- The repository root `pom.xml` includes `zookeeper-website` as a Maven module.
- `zookeeper-website/pom.xml` owns the website-specific build and configures `frontend-maven-plugin`.

The frontend plugin is intentionally bound only to the Maven `site` lifecycle:

- `pre-site` installs Node.js/npm and runs `npm ci`
- `site` runs the website CI command

Because of that, the website is built **only during site generation** (`mvn site`) and is skipped during regular Maven lifecycle phases such as `mvn clean install`.

#### When the Website Builds

The website build is triggered **only** when you run:

```bash
mvn site
```

The website will **NOT** build during regular commands like:

- `mvn clean install`
- `mvn package`
- `mvn compile`

This keeps regular ZooKeeper builds fast while still allowing the website to be generated when needed.

#### What the Website POM Does During `mvn site`

When you run `mvn site`, the website module automatically:

1. **Cleans previous build artifacts**
   - Removes `build/` directory
   - Removes `node_modules/` directory
   - Ensures a fresh build environment

2. **Installs Node.js v22.20.0 and npm 11.6.2** (if not already available)
   - Installed to `target/` directory
   - Does not affect your system Node/npm installation

3. **Runs `npm ci`** to install all dependencies
   - Reads from `package.json` and pins to `package-lock.json`
   - Installs to `node_modules/`

4. **Runs a website CI command**:
   - Default (`mvn site`): `npm run ci`
   - With test skipping (`mvn site -DskipTests`): `npm run ci-skip-tests`

   `npm run ci` executes:
   - `npm run fumadocs-init` - Intialize Fumadocs artifcats (`.source/`), so linter doesn't complain
   - `npm run lint` - ESLint code quality checks
   - `npm run typecheck` - TypeScript type checking
   - `npm run test:unit:run` - Vitest unit tests
   - `npm run build` - Production build
   - `npx playwright install` - Installs Playwright browsers
   - `npm run test:e2e` - Playwright e2e tests

   `npm run ci-skip-tests` executes:
   - `npm run build` - Production build

5. **Build Output**: Generated files are in `build/` directory

#### Maven Commands

**Build ZooKeeper WITHOUT the Website (default):**

```bash
# From ZooKeeper root directory
mvn clean install
```

**Build the Website:**

```bash
# From ZooKeeper root directory
mvn site
```

This generates the full ZooKeeper website including documentation and the React-based website.

**Build the Website While Skipping Test Suites:**

```bash
# From ZooKeeper root or zookeeper-website directory
mvn site -DskipTests
```

This runs `npm run ci-skip-tests` for the website module.

**Build Website Only:**

```bash
# From ZooKeeper root directory
mvn -pl zookeeper-website site

# Or from the website module directory
cd zookeeper-website
mvn site
```

### Deployment

The website source lives on the **`master`** branch of the [apache/zookeeper](https://github.com/apache/zookeeper) repository under `zookeeper-website/`. The live production site at [zookeeper.apache.org](https://zookeeper.apache.org) is served from the **`asf-site`** branch. Any commit pushed to `asf-site` is immediately reflected on the live site.

#### Basic workflow

Use this flow for any update to an already-published version: landing edits, docs fixes, news, maintenance. For shipping a new version, see [Publishing a New ZooKeeper Release](#publishing-a-new-zookeeper-release).

The end-to-end flow has two halves: **source changes land on `master` via PR**, then **the rebuilt site is published to `asf-site` by hand**. Because the landing site and the current docs are independent builds with independent asset hashes, they live in separate directories under `content/` on `asf-site` (landing at the root, current docs under `content/doc/r<CURRENT_VERSION>/`). If a part's source did not change, its rebuilt output is byte-identical and produces no `content/` diff, so seeing changes in only landing or only docs after a rebuild is expected. The current docs directory is self-contained (its own assets, `.htaccess`, and `llms-full.txt`); only the Maven-generated `apidocs/` is layered back in during publish.

The steps:

1. **Clone the repo** and check out `master`.
2. **Install dependencies** with `npm ci`.
3. **Make changes** on a feature branch based off `master`.
4. **Verify locally** by running the full CI pipeline (`npm run ci`).
5. **Open a PR and merge to `master`** after review.
6. **Build the production bundle** on `master` and stage `build/client/` into `/tmp`. The output is what will land under `content/` on `asf-site`: landing files at the root of `build/client/` and current docs under `build/client/doc/r<CURRENT_VERSION>/`.
7. **Switch to `asf-site`** and wipe everything not tracked by that branch with `git clean -fxd`.
8. **Stage the current version's `apidocs/`** out to `/tmp`. It is published by the Maven build (not by the website CI pipeline), so it must be preserved across the docs replace in step 10.
9. **Replace the landing files** at the root of `content/` from `/tmp`, leaving `content/doc/` untouched so every archived version stays in place.
10. **Replace the current docs directory** (`content/doc/r<CURRENT_VERSION>/`) with the freshly built one, then layer the staged `apidocs/` back in.
11. **Commit and push `asf-site`**, then clean up the staging directories under `/tmp`.

Step-by-step, in shell:

```bash
# Set this to the current docs version from app/lib/current-version.ts (the canary on master)
DOC_VERSION=3.9.5

# Step 1: Clone and check out master
git clone https://github.com/apache/zookeeper.git
cd zookeeper
git checkout master

# Step 2: Install dependencies
cd zookeeper-website
npm ci

# Step 3: Make changes
git checkout -b <your-branch>
# Edit content in zookeeper-website/, then continue...

# Step 4: Verify locally
npm run ci

# Step 5: Push, open a PR, merge to master
git add <changed files>
git commit -m "Update website content"
git push origin <your-branch>
# ...open a PR, get review, merge to master, then pull master locally before step 6.

# Step 6: Stage the build output to /tmp BEFORE switching branches
# Preventive measure to ensure no conflicts appear.
rm -rf /tmp/zookeeper-site-build
cp -R build/client /tmp/zookeeper-site-build

# Step 7: Switch to asf-site and clean
cd ..
git checkout asf-site
# Drop every untracked file left over from the master checkout
git clean -fxd

# Step 8: Preserve the current version's API docs
# Same preemptive-wipe pattern as above.
rm -rf /tmp/zookeeper-apidocs-r${DOC_VERSION}
cp -R content/doc/r${DOC_VERSION}/apidocs /tmp/zookeeper-apidocs-r${DOC_VERSION}

# Step 9: Replace landing files (everything in content/ except content/doc/)
find content -mindepth 1 -maxdepth 1 ! -name doc -exec rm -rf {} +
find /tmp/zookeeper-site-build -mindepth 1 -maxdepth 1 ! -name doc -exec cp -R {} content/ \;

# Step 10: Replace the current docs version, then restore its API docs
rm -rf content/doc/r${DOC_VERSION}
mkdir -p content/doc/r${DOC_VERSION}
cp -R /tmp/zookeeper-site-build/doc/r${DOC_VERSION}/. content/doc/r${DOC_VERSION}/
cp -R /tmp/zookeeper-apidocs-r${DOC_VERSION} content/doc/r${DOC_VERSION}/apidocs

# Step 11: Commit, push, clean up
git add content
git commit -m "Publish website <date>"
git push origin asf-site

rm -rf /tmp/zookeeper-site-build /tmp/zookeeper-apidocs-r${DOC_VERSION}
```

Once `asf-site` is pushed the updates are live within minutes.

---

### Publishing a New ZooKeeper Release

A ZooKeeper release follows the established Apache "cut a release branch off the line branch" pattern, and the website piggybacks on it. The big picture is:

1. **Prepare** `master` for the release — update landing content, version constants, MDX docs, etc. `CURRENT_VERSION` should already match the version being released.
2. **Cut the release branch** — e.g. `branch-3.9` -> `branch-3.9.6`, push.
3. **Build the docs from the release branch** so the rendered HTML pins to that tag rather than to whatever is on `master`.
4. **Publish on `asf-site`** — add `content/doc/r3.9.6/`, repoint `content/doc/current` to it, replace the landing pages in the root directory.

Example: current release is **3.9.5**, new release is **3.9.6**. The line branch is **`branch-3.9`**. Throughout this section: `OUTGOING=3.9.5`, `CURRENT=3.9.6`.

#### Step 1 — Prepare landing + docs source on `master`

Done on the `master` branch, merged through normal PRs **before** the release branch is cut.

1. Confirm `CURRENT_VERSION` in [`app/lib/current-version.ts`](app/lib/current-version.ts) already equals the release being prepared (e.g. `"3.9.6"`). The bump should have happened when work on this version started. If it doesn't, do the bump now as a normal PR (see [Bumping `CURRENT_VERSION`](#bumping-current_version-canary)).
2. Update landing pages that reference the version: `releases`, `news`, anywhere else that calls out the latest version by string.
3. Revisit `LTS_VERSIONS` in [`app/lib/released-docs-versions.ts`](app/lib/released-docs-versions.ts) if this release changes long-term-support membership. By convention `CURRENT_VERSION` is the **first** entry — these versions get pinned to the top of the navbar's **Documentation** dropdown. `RAW_RELEASED_DOC_VERSIONS_LIST` already terminates with `CURRENT_VERSION`, so the new version is exposed automatically.
4. Update MDX under `app/pages/_docs/docs/_mdx/` for the new release (release notes, version-specific instructions, etc.).
5. Run `npm ci && npm run ci` locally and open a PR. Merge once green.

#### Step 2 — Cut the release branch

This is the standard ZooKeeper release-branching step (e.g. `branch-3.9` → `branch-3.9.6`).

#### Step 3 — Build docs and landing from the release branch

```bash
CURRENT=3.9.6

# Working from branch-3.9.6 on a clean checkout
cd zookeeper-website
npm ci
npm run ci

# Stage the build output before we ever switch branches.
rm -rf /tmp/zookeeper-site-build
cp -R build/client /tmp/zookeeper-site-build

# Stage the Java API docs generated by the Maven build for this release.
cd ..
rm -rf /tmp/zookeeper-apidocs-r${CURRENT}
cp -R <path-to-generated-apidocs> /tmp/zookeeper-apidocs-r${CURRENT}
```

The fresh `build/client/doc/r${CURRENT}/` directory is the contents of `doc/r3.9.6/` that will be added to `asf-site` in Step 4.

#### Step 4 — Publish on `asf-site`

```bash
git checkout asf-site
git clean -fxd

# 4a. Add the new docs version
mkdir -p content/doc/r${CURRENT}
cp -R /tmp/zookeeper-site-build/doc/r${CURRENT}/. content/doc/r${CURRENT}/
cp -R /tmp/zookeeper-apidocs-r${CURRENT} content/doc/r${CURRENT}/apidocs

# 4b. Repoint content/doc/current at the new release.
# `content/doc/current` is a symlink (NOT a directory) whose target is
# the current `r<version>` directory. Update it in place so URLs under
# /doc/current/... resolve to the new release.
ln -sfn r${CURRENT} content/doc/current

# 4c. Replace the landing pages (everything in content/ except content/doc/).
find content -mindepth 1 -maxdepth 1 ! -name doc -exec rm -rf {} +
find /tmp/zookeeper-site-build -mindepth 1 -maxdepth 1 ! -name doc -exec cp -R {} content/ \;

git add content
git commit -m "Publish ZooKeeper ${CURRENT}"
git push origin asf-site

# Cleanup
rm -rf \
  /tmp/zookeeper-site-build \
  /tmp/zookeeper-apidocs-r${CURRENT}
```

Once `asf-site` is pushed the live site reflects the release within minutes.

#### Bumping `CURRENT_VERSION` (canary)

`CURRENT_VERSION` on `master` is bumped **once per upcoming release**, at the moment the team starts working on that version.

Files to edit:

- **[`app/lib/current-version.ts`](app/lib/current-version.ts)** — bump `CURRENT_VERSION` to the new canary (e.g. `"3.9.7"`).
- **[`app/lib/released-docs-versions.ts`](app/lib/released-docs-versions.ts)** — append the version that just shipped to `RAW_RELEASED_DOC_VERSIONS_LIST` (it now has a published `content/doc/r<version>/` directory on `asf-site`), and revisit `LTS_VERSIONS`: the new `CURRENT_VERSION` should be first, and the rest of the list may need entries added, removed, or swapped depending on which lines are still being supported.

```bash
git checkout master
git pull origin master
git checkout -b bump-current-version

# Edit the two files described above.

cd zookeeper-website
npm ci
npm run ci
cd ..

git commit -am "Bump CURRENT_VERSION canary to 3.9.7"
git push -u origin bump-current-version
# open a PR, merge.
```

After this lands, every subsequent docs edit on `master` accumulates under the new canary until the next release branch is cut.

### Troubleshooting

#### TypeScript Types Are Broken

If you see type errors related to React Router's `+types`, regenerate them:

```bash
npx react-router typegen
```

#### Port Already in Use

If `npm run dev` fails because port 5173 is in use:

```bash
# Kill the process using the port
lsof -ti:5173 | xargs kill -9

# Or change the port in vite.config.ts
```

#### Build Fails

1. **Clear generated files:**

   ```bash
   rm -rf build/ node_modules/ .vite/ .react-router/ .source/
   ```

2. **Reinstall dependencies:**

   ```bash
   npm ci
   ```

3. **Try building again:**
   ```bash
   npm run build
   ```

---

## Additional Resources

- **React Router Documentation**: https://reactrouter.com/
- **Progressive Enhancement Explained**: https://reactrouter.com/explanation/progressive-enhancement
- **shadcn/ui Components**: https://ui.shadcn.com/
- **TailwindCSS Docs**: https://tailwindcss.com/
- **TypeScript Handbook**: https://www.typescriptlang.org/docs/

---

Built with ❤️ for the Apache ZooKeeper community.
