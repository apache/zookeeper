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
    - [Building Archived Docs](#building-archived-docs)
    - [Continuous Integration](#continuous-integration)
  - [Maven Integration](#maven-integration)
  - [Deployment](#deployment)
  - [Publishing a New ZooKeeper Release](#publishing-a-new-zookeeper-release)
  - [Troubleshooting](#troubleshooting)

---

## Content Editing

Most landing pages store content in **Markdown (`.md`)** or **JSON (`.json`)** files located in `app/pages/_landing/[page-name]/`. Docs content lives under `app/pages/_docs/` and is authored in MDX.

**Examples:**

- `app/pages/_landing/credits/developers.json` - JSON data for developers
- `app/pages/_landing/releases/index.tsx` - Releases page
- `app/pages/_docs/docs/_mdx/...` - MDX content for documentation

---

## Development

### Prerequisites

Before you begin, ensure you have the following installed:

- **Node.js version 22** - JavaScript runtime (like the JVM for Java)
  - Download from [nodejs.org](https://nodejs.org/)
  - Verify installation: `node --version` (should show v22.12 or newer)

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
  - Some features (search, theme toggle, interactive menus) require JavaScript

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

5. **Two Layout Systems in One App**:
   - **Landing pages** live under `app/pages/_landing/` and use the landing layout.
   - **Docs pages** live under `app/pages/_docs/` and use Fumadocs layouts.
   - Both are part of the same React Router application, but render with different layouts and visual styles.

6. **Documentation Structure**:
   - **Docs** live under `app/pages/_docs/docs/_mdx/` and are the source of truth.

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
npm install
```

This downloads all required packages from npm (similar to Maven Central).

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

**Docs link & image conventions:**

- Internal doc links are **doc-root-relative** — write `/admin-ops/cli`, not `/docs/...` and not a hardcoded `/doc/r<version>/...`. The version prefix (`/doc/r<version>`) is added automatically at render time (`resolveDocsHref` on the live site, React Router `basename` in an archive).
- Images use the static asset path `/docs-images/...` (served from `public/docs-images/`).
- Use absolute `https://` URLs for off-site links (e.g. `https://zookeeper.apache.org/`).

**Update content:**

- Edit the appropriate `.md` or `.json` file
- Changes appear automatically

**Add a UI component:**

- Check if shadcn/ui has what you need first
- Only create custom components if necessary

**Update the 404 page:**

There are two separate 404 mechanisms:

- **Static Apache 404** (`public/404.html`): Served by Apache for requests that never reach the SPA (missing static files, direct URL hits). Configure handling in `public/.htaccess` (`ErrorDocument 404 /404.html`). Keep `public/robots.txt` disallowing `/404.html` so the error page is not indexed.
- **In-app error boundary** (`app/root.tsx`): Handles unknown routes after the React app loads (for example, invalid docs paths). Edit `ErrorBoundary` in `root.tsx` to change that experience.

**Update sitemap generation:**

- Edit `scripts/generate-sitemap.ts` and its tests in `scripts/generate-sitemap.test.ts`.
- `npm run build` generates `build/client/sitemap.xml`, and `public/robots.txt` should keep pointing at the production sitemap URL.

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

#### Building Archived Docs

To snapshot a released documentation version, build a versioned website archive:

```bash
npm run build:docs-archive -- 3.9.6
```

This creates `build/doc/r3.9.6/`, ready to copy to `asf-site` at `content/doc/r3.9.6/`.

Before building the archive, this command runs `npm run ci` without the archive base path. That verifies lint, typecheck, unit tests, the normal production build, and Playwright e2e tests. It then rebuilds with the archive base path and validates the generated archive output, including that every source MDX docs page has a corresponding archive HTML page.

Archived docs use the same versioned route shape as current docs:

- `/doc/r3.9.6/`
- `/doc/r3.9.6/overview/quick-start`
- `/doc/r3.9.6/developer/programmers-guide`

The archive build uses React Router's `basename` and Vite's `base` to serve docs from `/doc/r<version>/`. It uses the archive route set from `app/routes.ts`: docs and search remain available in the archive, while non-doc archive-local requests such as `/doc/r3.9.6/news` are redirected by the generated `.htaccess` file to the matching live-site path (`/news`).

Archive builds set the `ZOOKEEPER_DOCS_ARCHIVE_BASE` env var. Vite turns it into the app `base`, which the browser reads back as `import.meta.env.BASE_URL`. Rule of thumb: Node-side code (vite/react-router configs, scripts) reads `ZOOKEEPER_DOCS_ARCHIVE_BASE` from `process.env`; bundled app code reads `import.meta.env.BASE_URL`. They are the same value in two execution contexts.

The archive script packages the generated docs into a self-contained directory. React Router prerenders archive HTML under `build/client/doc/r<version>/`, while Vite and `public/` assets are emitted at the build root (`build/client/assets/`, `build/client/docs-images/`, `build/client/fonts/`, `build/client/images/`, `build/client/favicon.ico`). The script copies the docs HTML plus those known static assets into `build/doc/r<version>/` so URLs such as `/doc/r3.9.6/assets/...` exist after publishing. If new top-level static folders or asset roots are introduced, update `scripts/build-docs-archive.ts` so they are copied, URL-rewritten if needed, and covered by archive validation.

Archive output intentionally does not copy the live site's root-only files such as `404.html`, `robots.txt`, `__spa-fallback.html`, or the root `.htaccess`. Archives get their own generated `.htaccess`: existing archive files are served directly, and missing archive-local paths redirect to the matching live-site path (for example `/doc/r3.9.6/news` redirects to `/news`). The archive keeps only docs routes and `api/search`.

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

- `pre-site` installs Node.js/npm and runs `npm install`
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

3. **Runs `npm install`** to install all dependencies
   - Reads from `package.json`
   - Installs to `node_modules/`

4. **Runs a website CI command**:
   - Default (`mvn site`): `npm run ci`
   - With test skipping (`mvn site -DskipTests`): `npm run ci-skip-tests`

   `npm run ci` executes:
   - `npm run fumadocs-init` - Initialize Fumadocs
   - `npm run lint` - ESLint code quality checks
   - `npm run typecheck` - TypeScript type checking
   - `npm run test:unit:run` - Vitest unit tests
   - `npm run build` - Production build
   - `npx playwright install` - Installs Playwright browsers
   - `npm run test:e2e` - Playwright e2e tests

   `npm run ci-skip-tests` executes:
   - `npm run fumadocs-init` - Initialize Fumadocs
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

The website source lives on the **`master`** branch of the [apache/zookeeper](https://github.com/apache/zookeeper) repository under `zookeeper-website/`. The live production site at [zookeeper.apache.org](https://zookeeper.apache.org) is served from the **`asf-site`** branch. Any commit pushed to `asf-site` is immediately reflected on the live site via Apache's gitpubsub infrastructure.

#### Basic workflow

1. Make changes on a feature branch based off `master`.
2. Run the CI pipeline locally to verify everything passes.
3. Merge to `master` after review.
4. Build the production bundle — the output ends up in `build/client/`.
5. Publish to `asf-site`, preserving docs and versioned API docs under `content/doc/`.

```bash
# 1. Clone the repository and work from master
git clone https://github.com/apache/zookeeper.git
cd zookeeper
git checkout master

# 2. Edit content in zookeeper-website/, then verify
cd zookeeper-website
npm run ci

# 3. Commit the source changes on your feature branch, open a PR, and merge to master
git add <changed files>
git commit -m "Update website content"
git push origin <your-branch>

# 4. Publish: copy the build output, then switch to asf-site
# Set this to the current docs version from app/lib/current-version.ts
DOC_VERSION=3.9.5
cp -R build/client /tmp/zookeeper-site-build
cd ..
git checkout asf-site

# Preserve current version API docs before replacing generated docs
rm -rf /tmp/zookeeper-apidocs-r${DOC_VERSION}
cp -R content/doc/r${DOC_VERSION}/apidocs /tmp/zookeeper-apidocs-r${DOC_VERSION}

# Replace the website root while preserving all published docs under content/doc/
mkdir -p content
find content -mindepth 1 -maxdepth 1 ! -name doc -exec rm -rf {} +
find /tmp/zookeeper-site-build -mindepth 1 -maxdepth 1 ! -name doc -exec cp -R {} content/ \;

# Replace the current docs version with the fresh build, then restore API docs
rm -rf content/doc/r${DOC_VERSION}
mkdir -p content/doc/r${DOC_VERSION}
cp -R /tmp/zookeeper-site-build/doc/r${DOC_VERSION}/. content/doc/r${DOC_VERSION}/
cp -R /tmp/zookeeper-apidocs-r${DOC_VERSION} content/doc/r${DOC_VERSION}/apidocs

git add content
git commit -m "Publish website <date>"
git push origin asf-site

rm -rf /tmp/zookeeper-site-build /tmp/zookeeper-apidocs-r${DOC_VERSION}
```

Once `asf-site` is pushed the updates are live within minutes.

---

### Publishing a New ZooKeeper Release

When a new ZooKeeper version ships, archive the outgoing docs on `asf-site`, bump the current version on `master`, and publish the new site plus API docs. Run both builds on `master`, copy outputs to `/tmp`, then switch to `asf-site` once.

Example: current release is **3.9.5**, new release is **3.9.6**.

Copy build outputs to `/tmp` before switching branches. `build/` is gitignored and is easy to lose when checking out another branch or cleaning untracked files.

#### Step 1 — Prepare on `master`

**1. Archive build for the outgoing version** — run this **before** bumping `CURRENT_VERSION`:

```bash
cd zookeeper-website
OUTGOING=3.9.5
CURRENT=3.9.6

npm run build:docs-archive -- ${OUTGOING}
rm -rf /tmp/zookeeper-archive-r${OUTGOING}
cp -R build/doc/r${OUTGOING} /tmp/zookeeper-archive-r${OUTGOING}
```

This creates a self-contained archive at `build/doc/r3.9.5/`. See [Building Archived Docs](#building-archived-docs) for details.

**2. Update source for the new release:**

- Add the outgoing version to `REACT_ROUTER_RELEASED_DOC_VERSIONS` in `app/lib/released-docs-versions.ts` (without the `r` prefix). Existing pre-migration archives stay in `LEGACY_RELEASED_DOC_VERSIONS` and link to `/doc/r<version>/index.html`; React Router archives link to `/doc/r<version>/`:

```typescript
export const REACT_ROUTER_RELEASED_DOC_VERSIONS = new Set([
  // ...
  "3.9.5"
]);
```

Keep these lists in sync with the directories published in `asf-site`.

- Bump `CURRENT_VERSION` in `app/lib/current-version.ts`:

```typescript
export const CURRENT_VERSION = "3.9.6";
```

- Update MDX under `app/pages/_docs/docs/_mdx/` for the new release.


**3. Build the new release site and stage API docs:**

```bash
# Still in zookeeper-website/

npm run ci
cp -R build/client /tmp/zookeeper-site-build

# Generate Java API docs with the Maven build, then stage them:
cd ..
cp -R <path-to-generated-apidocs> /tmp/zookeeper-apidocs-r${CURRENT}
```

#### Step 2 — Publish on `asf-site` (once)

```bash
# Currently in the root directory

git checkout asf-site

# Archive outgoing docs (preserve existing API docs on asf-site)
cp -R content/doc/r${OUTGOING}/apidocs /tmp/zookeeper-apidocs-r${OUTGOING}
rm -rf content/doc/r${OUTGOING}
cp -R /tmp/zookeeper-archive-r${OUTGOING}/. content/doc/r${OUTGOING}/
cp -R /tmp/zookeeper-apidocs-r${OUTGOING} content/doc/r${OUTGOING}/apidocs

# Deploy landing page and current docs, then add API docs for the new release
find content -mindepth 1 -maxdepth 1 ! -name doc -exec rm -rf {} +
find /tmp/zookeeper-site-build -mindepth 1 -maxdepth 1 ! -name doc -exec cp -R {} content/ \;

mkdir -p content/doc/r${CURRENT}
cp -R /tmp/zookeeper-site-build/doc/r${CURRENT}/. content/doc/r${CURRENT}/
cp -R /tmp/zookeeper-apidocs-r${CURRENT} content/doc/r${CURRENT}/apidocs

git add content
git commit -m "Publish ZooKeeper ${CURRENT}"
git push origin asf-site

rm -rf \
  /tmp/zookeeper-archive-r${OUTGOING} \
  /tmp/zookeeper-site-build \
  /tmp/zookeeper-apidocs-r${OUTGOING} \
  /tmp/zookeeper-apidocs-r${CURRENT}
```

For routine updates to the current version (without a release), use the [basic deployment workflow](#basic-workflow) instead.

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
   npm i
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
