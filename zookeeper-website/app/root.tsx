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
  let message = "Oops!";
  let details = "An unexpected error occurred.";
  let stack: string | undefined;

  if (isRouteErrorResponse(error)) {
    message = error.status === 404 ? "404" : "Error";
    details =
      error.status === 404
        ? "The requested page could not be found."
        : error.statusText || details;
  } else if (import.meta.env.DEV && error && error instanceof Error) {
    details = error.message;
    stack = error.stack;
  }

  return (
    <main className="container mx-auto p-4 pt-16">
      <h1>{message}</h1>
      <p>{details}</p>
      {stack && (
        <pre className="w-full overflow-x-auto p-4">
          <code>{stack}</code>
        </pre>
      )}
    </main>
  );
}
