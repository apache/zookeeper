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
  - [Building for Production](#building-for-production)
  - [Maven Integration](#maven-integration)
  - [Deployment](#deployment)
  - [Publishing a New ZooKeeper Release](#publishing-a-new-zookeeper-release)
  - [Troubleshooting](#troubleshooting)

---

## Content Editing

Most landing pages store content in **Markdown (`.md`)** or **JSON (`.json`)** files located in `app/pages/_landing/[page-name]/`. Docs content lives under `app/pages/_docs/` and is authored in MDX.

Legacy documentation is preserved for those users who have old bookmarked links and notes: the old book lives at `/public/book.html`, and its static assets are in `public/old-book-static-files/`.

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
  - Verify installation: `node --version` (should show v20.19+ or v22.12+)

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

- **Without JavaScript**: Users still get a fully functional website
  - All links and forms work via traditional HTML
  - Content is accessible to everyone
  - Better for search engines and accessibility tools

This approach ensures the website works for all users, regardless of their browser capabilities or connection speed.

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
my-react-router-app/
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
│   │   └── markdown-layout.tsx        # Layout for markdown content pages
│   │
│   ├── pages/                         # Complete pages (composed of ui + components)
│   │   ├── _landing/                  # Landing pages + layout
│   │   │   ├── home/                  # Home page
│   │   │   │   ├── index.tsx          # Main page component (exported)
│   │   │   │   ├── hero.tsx           # Hero section (not exported)
│   │   │   │   ├── features.tsx       # Features section (not exported)
│   │   │   │   └── ...
│   │   │   ├── team/                  # Landing page content
│   │   │   └── ...
│   │   ├── _docs/                     # Documentation (Fumadocs)
│   │   │   ├── docs/                  # MDX content and structure
│   │   │   ├── docs-layout.tsx        # Fumadocs layout wrapper
│   │   │   └── ...
│   │
│   ├── routes/                        # Route definitions and metadata
│   │   ├── home.tsx                   # Home route configuration
│   │   ├── team.tsx                   # Team route configuration
│   │   └── ...
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
  <Link to="/team">Team</Link>
);
```

```typescript
// ❌ WRONG - Do not import Link from react-router
import { Link } from "react-router";

export const MyComponent = () => (
  <Link to="/team">Team</Link>
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

**Add a new page:**

1. Create directory in `app/pages/my-new-page/`
2. Create `index.tsx` in that directory
3. Create route file in `app/routes/my-new-page.tsx`
4. Register route in `app/routes.ts`

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

- Edit the content in `app/routes/404.tsx`.
- Apache 404 handling lives in `public/.htaccess` (uses `ErrorDocument 404 /404`).

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

Use the `renderWithProviders` utility in `test/utils.tsx` to ensure components have access to routing and theme context:

```typescript
import { renderWithProviders, screen } from './utils'
import { MyComponent } from '@/components/my-component'

describe('MyComponent', () => {
  it('renders correctly', () => {
    renderWithProviders(<MyComponent />)
    expect(screen.getByText('Hello World')).toBeInTheDocument()
  })
})
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

**Skip Website Build:**

If you are running the Maven `site` lifecycle but want to disable the website frontend build:

```bash
# From ZooKeeper root or zookeeper-website directory
mvn site -DskipSite
```

### Deployment

The website source lives on the **`website`** branch of the [apache/zookeeper](https://github.com/apache/zookeeper) repository. The live production site at [zookeeper.apache.org](https://zookeeper.apache.org) is served from the **`asf-site`** branch. Any commit pushed to `asf-site` is immediately reflected on the live site via Apache's gitpubsub infrastructure.

#### Basic workflow

1. Make changes on the `website` branch.
2. Run the CI pipeline locally to verify everything passes.
3. Build the production bundle — the output ends up in `build/client/`.
4. Replace the contents of `asf-site` with the new `build/client/` and push.

```bash
# 1. Clone / switch to the website source branch
git clone -b website https://github.com/apache/zookeeper.git
cd zookeeper

# 2. Edit content, then verify
npm run ci

# 3. Commit the source changes
git add <changed files>
git commit -m "Update website content"
git push origin website

# 4. Publish: switch to asf-site and replace content
git checkout asf-site
rm -rf content
mkdir -p content
cp -R build/client/. content/
git add content
git commit -m "Publish website <date>"
git push origin asf-site
```

Once `asf-site` is pushed the updates are live within minutes.

---

### Publishing a New ZooKeeper Release

When a new ZooKeeper version is released, update the **current version** identifier and archive the previous release's generated documentation.

#### Step 1 — Archive the outgoing documentation

The built HTML of the outgoing release docs must be preserved so users can still access them via the "Older docs" picker in the sidebar and navbar. Each archived version lives in its own folder under `public/released-docs/`, named `r<version>` (e.g. `r3.9.4`).

Copy the fully-rendered HTML documentation into that folder:

```bash
# Example: archiving 3.9.4 before bumping to 3.9.5
mkdir -p public/released-docs/r3.9.4
cp -R <path-to-3.9.4-docs-html>/* public/released-docs/r3.9.4/
```

> The "Older docs" picker reads folder names from `public/released-docs/` at build time — no configuration file needs to be updated.

#### Step 2 — Bump `CURRENT_VERSION`

Open `app/lib/current-version.ts` and update the version string:

```typescript
// app/lib/current-version.ts
export const CURRENT_VERSION = "3.9.5"; // ← change to the new version
```

This single constant drives the version shown across the current docs experience, including:

- The docs overview page title and description (`3.9.5 Overview`, `Official Apache ZooKeeper 3.9.5 documentation…`)
- Other components that reference `CURRENT_VERSION`

#### Step 3 — Update the in-app documentation

The current release's documentation source lives in `app/pages/_docs/docs/_mdx/`. Update or replace the MDX files there to reflect the new release.

#### Step 4 — Build and publish

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
