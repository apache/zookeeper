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

This creates `build/released-docs/r3.9.6/`, ready to copy to `asf-site` at `content/released-docs/r3.9.6/`.

Before building the archive, this command runs `npm run ci` without the archive base path. That verifies lint, typecheck, unit tests, the normal production build, and Playwright e2e tests. It then rebuilds with the archive base path and validates the generated archive output.

Archived docs keep the normal website route structure under the versioned base path:

- `/released-docs/r3.9.6/docs/`
- `/released-docs/r3.9.6/docs/overview/quick-start`
- `/released-docs/r3.9.6/docs/developer/programmers-guide`

The archive build uses React Router's `basename` and Vite's `base` to serve the site from `/released-docs/r<version>/`. It uses the archive route set from `app/routes.ts`: docs and search remain available in the archive, while every other client-side route is handled by a catch-all redirect route that sends users to the matching live-site path. The archive also writes a local `.htaccess` file so direct non-doc requests under `/released-docs/r<version>/` are redirected by Apache.

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
5. Publish to `asf-site`, preserving archived docs under `content/released-docs/` and generated API docs under `content/apidocs/`.

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
cp -R build/client /tmp/zookeeper-site-build
cd ..
git checkout asf-site

# Preserve archived docs and API docs before replacing content
if [ -d content/released-docs ]; then
  cp -R content/released-docs /tmp/released-docs-backup
fi
if [ -d content/apidocs ]; then
  cp -R content/apidocs /tmp/apidocs-backup
fi

rm -rf content
mkdir -p content
cp -R /tmp/zookeeper-site-build/. content/

# Restore archived docs and API docs
if [ -d /tmp/released-docs-backup ]; then
  cp -R /tmp/released-docs-backup content/released-docs
fi
if [ -d /tmp/apidocs-backup ]; then
  cp -R /tmp/apidocs-backup content/apidocs
fi

git add content
git commit -m "Publish website <date>"
git push origin asf-site
```

Once `asf-site` is pushed the updates are live within minutes.

---

### Publishing a New ZooKeeper Release

When a new ZooKeeper version is released, update the **current version** identifier in `master`, add the outgoing version to the released-docs list, and archive the outgoing generated documentation in the `asf-site` branch.

#### Step 1 — Archive the outgoing documentation

The outgoing release docs must be preserved so users can still access them via the "Older docs" picker in the sidebar and navbar. Archived docs are stored only in the published `asf-site` branch to avoid keeping generated archives in `master`.

Build a versioned website archive for the outgoing version:

```bash
# On master — example: archiving 3.9.6 before publishing the next version
cd zookeeper-website
npm run build:docs-archive -- 3.9.6
```

This creates `build/released-docs/r3.9.6/`. Docs pages keep the normal `/docs` route prefix under the archive base, so the deployed docs entry page is `/released-docs/r3.9.6/docs/` and docs pages use URLs such as `/released-docs/r3.9.6/docs/developer/programmers-guide`. Non-doc archive entry pages redirect to `/`.

The command runs the full normal website CI checks before creating the archive, then performs archive-specific output validation after the archive build.

Copy the generated archive into the `asf-site` branch:

```bash
# On the asf-site branch
git checkout asf-site
rm -rf content/released-docs/r3.9.6
mkdir -p content/released-docs/r3.9.6
cp -R <path-to-zookeeper-website>/build/released-docs/r3.9.6/. content/released-docs/r3.9.6/
git add content/released-docs/r3.9.6
git commit -m "Archive docs for 3.9.6"
git push origin asf-site
```

When publishing a new site build, preserve `content/released-docs/` as shown in the [deployment steps](#deployment) above.

#### Step 2 — Update the released-docs version list

Open `app/lib/released-docs-versions.ts` and add the outgoing version to the matching archive list without the leading `r` prefix. Existing pre-migration archives remain in `LEGACY_RELEASED_DOC_VERSIONS` and link to `/released-docs/r<version>/index.html`. Archives produced by the React Router website build go in `REACT_ROUTER_RELEASED_DOC_VERSIONS` and link to `/released-docs/r<version>/docs/`:

```typescript
export const LEGACY_RELEASED_DOC_VERSIONS = new Set([
  // ...
  "3.9.4"
]);

export const REACT_ROUTER_RELEASED_DOC_VERSIONS = new Set([
  // ...
  "3.9.6"
]);
```

The website combines these lists into `RELEASED_DOC_VERSIONS` for display, while `getReleasedDocUrl()` preserves the correct URL shape for each archive type. Keep these lists in sync with the directories published in `asf-site`.

#### Step 3 — Bump `CURRENT_VERSION`

Open `app/lib/current-version.ts` and update the version string:

```typescript
// app/lib/current-version.ts
export const CURRENT_VERSION = "3.9.5"; // ← change to the new version
```

This single constant drives the version shown across the current docs experience, including:

- The docs overview page title and description (`3.9.5 Overview`, `Official Apache ZooKeeper 3.9.5 documentation…`)
- Other components that reference `CURRENT_VERSION`

#### Step 4 — Update the in-app documentation

The current release's documentation source lives in `app/pages/_docs/docs/_mdx/`. Update or replace the MDX files there to reflect the new release.

#### Step 5 — Publish API docs and update the docs nav link

Java API documentation is generated by the Maven build and published separately on `asf-site`. Current API docs live under `content/apidocs/` (for example `content/apidocs/zookeeper-server/index.html`). Versioned archive API docs should live under the docs archive, for example `content/released-docs/r3.9.6/apidocs/zookeeper-server/index.html`.

After generating API docs for the new release, copy them to `asf-site`:

```bash
# On the asf-site branch — example: publishing API docs for 3.9.5
git checkout asf-site
rm -rf content/apidocs
mkdir -p content/apidocs
cp -R <path-to-generated-apidocs>/* content/apidocs/
git add content/apidocs
git commit -m "Publish API docs for 3.9.5"
git push origin asf-site
```

To make an archived docs snapshot self-contained, also copy the outgoing version's API docs into that archive:

```bash
# On the asf-site branch — example: API docs for the 3.9.6 archive
mkdir -p content/released-docs/r3.9.6/apidocs
cp -R <path-to-generated-apidocs>/* content/released-docs/r3.9.6/apidocs/
git add content/released-docs/r3.9.6/apidocs
git commit -m "Archive API docs for 3.9.6"
git push origin asf-site
```

The **API Docs** entry in the developer docs sidebar is a hardcoded absolute path in `app/pages/_docs/docs/_mdx/developer/meta.json`:

```json
"external:[API Docs](/apidocs/zookeeper-server/index.html)"
```

Unlike most docs links, this URL is not relative and does not derive from `CURRENT_VERSION`. The archive build runs with Vite's `base` set to `/released-docs/r<version>/` and postprocesses literal archive-local URLs, so archived docs point at `content/released-docs/r<version>/apidocs/`. Update the `meta.json` entry manually whenever the published API docs location changes.

When publishing a new site build, preserve `content/apidocs/` as shown in the [deployment steps](#deployment) above.

#### Step 6 — Build and publish

```bash
npm run ci          # verify everything passes
# then follow the deployment steps above to push to asf-site
```

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
