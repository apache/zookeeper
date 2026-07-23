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
  isRouteErrorResponse,
  Links,
  Meta,
  Outlet,
  Scripts,
  ScrollRestoration
} from "react-router";

import type { Route } from "./+types/root";
import appStyles from "./app.css?url";
import "katex/dist/katex.css";
import { ThemeProvider } from "./lib/theme-provider";
import { CURRENT_DOCS_PATH } from "./lib/docs-paths";

export const links: Route.LinksFunction = () => [
  {
    rel: "preload",
    as: "font",
    href: "/fonts/inter-latin-wght-normal.woff2",
    type: "font/woff2",
    crossOrigin: "anonymous"
  },
  {
    rel: "prefetch",
    as: "font",
    href: "/fonts/inter-latin-wght-italic.woff2",
    type: "font/woff2",
    crossOrigin: "anonymous"
  },
  { rel: "stylesheet", href: appStyles },
  { rel: "icon", href: "/images/logo.svg", type: "image/svg+xml" },
  { rel: "icon", href: "/favicon.ico", sizes: "any" }
];

export function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <meta charSet="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <Meta />
        <Links />
        <script
          dangerouslySetInnerHTML={{
            __html: `
              (function() {
                const theme = localStorage.getItem('theme');
                const root = document.documentElement;
                root.classList.remove('light', 'dark');
                
                if (theme && ['light', 'dark'].includes(theme)) {
                  root.classList.add(theme);
                } else {
                  const systemTheme = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
                  root.classList.add(systemTheme);
                  localStorage.setItem('theme', systemTheme);
                }
              })();
            `
          }}
        />
        <noscript>
          <style>{`.theme-toggle-wrapper { display: none !important; }`}</style>
        </noscript>
      </head>
      <body className="font-base">
        <ThemeProvider defaultTheme="light">
          {children}

          <ScrollRestoration />
          <Scripts />
        </ThemeProvider>
      </body>
    </html>
  );
}

export default function App() {
  return <Outlet />;
}

export function ErrorBoundary({ error }: Route.ErrorBoundaryProps) {
  let message = "Something went wrong";
  let details =
    "An unexpected error occurred while loading this page. Please try again or return home.";
  let stack: string | undefined;
  let eyebrow = "Error";

  if (isRouteErrorResponse(error)) {
    eyebrow = String(error.status);

    if (error.status === 404) {
      message = "Page not found";
      details =
        "The website was updated recently, so the route you were trying to visit might have changed.";
    } else {
      message = error.statusText || message;
      details =
        typeof error.data === "string" && error.data.trim().length > 0
          ? error.data
          : details;
    }
  } else if (import.meta.env.DEV && error && error instanceof Error) {
    eyebrow = "Development error";
    details = error.message;
    stack = error.stack;
  }

  return (
    <main className="grid min-h-screen place-items-center px-4 py-16">
      <section
        className="mx-auto flex max-w-2xl flex-col items-center text-center"
        aria-labelledby="error-title"
      >
        <p className="text-muted-foreground text-sm font-semibold tracking-[0.3em] uppercase">
          {eyebrow}
        </p>
        <h1
          id="error-title"
          className="mt-4 text-4xl font-semibold tracking-tight text-balance md:text-6xl"
        >
          {message}
        </h1>
        <p className="text-muted-foreground mt-4 text-lg text-pretty md:text-xl">
          {details}
        </p>
        <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
          <a
            className="bg-primary text-primary-foreground focus-visible:ring-primary/50 hover:bg-primary/90 inline-flex h-10 items-center justify-center gap-2 rounded-md px-6 text-sm font-medium whitespace-nowrap shadow-sm transition-all focus-visible:ring-[3px] focus-visible:outline-none"
            href="/"
          >
            Go back home
          </a>
          <a
            className="border-border bg-background hover:bg-accent hover:text-accent-foreground inline-flex h-10 items-center justify-center gap-2 rounded-md border px-6 text-sm font-medium whitespace-nowrap shadow-xs transition-all focus-visible:ring-[3px] focus-visible:outline-none"
            href={CURRENT_DOCS_PATH}
          >
            Browse docs
          </a>
        </div>
      </section>
      {stack && (
        <pre className="bg-muted text-muted-foreground border-border mx-auto mt-8 max-h-96 w-full max-w-4xl overflow-x-auto rounded-lg border p-4 text-left text-sm">
          <code>{stack}</code>
        </pre>
      )}
    </main>
  );
}
