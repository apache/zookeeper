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
  type AdvancedIndex,
  type AdvancedOptions,
  createI18nSearchAPI,
  type SearchAPI,
  createSearchAPI
} from "fumadocs-core/search/server";
import { PathUtils } from "fumadocs-core/source";
import type { Language } from "@orama/orama";
import type { LoaderConfig, LoaderOutput, Page } from "fumadocs-core/source";
import type { I18nConfig } from "fumadocs-core/i18n";
import { findPath } from "fumadocs-core/page-tree";
import type { StructuredData } from "fumadocs-core/mdx-plugins";

type Awaitable<T> = T | Promise<T>;

function defaultBuildIndex<C extends LoaderConfig>(
  source: LoaderOutput<C>,
  tag?: (pageUrl: string) => string
) {
  function isBreadcrumbItem(item: unknown): item is string {
    return typeof item === "string" && item.length > 0;
  }

  return async (page: Page): Promise<AdvancedIndex> => {
    let breadcrumbs: string[] | undefined;
    let structuredData: StructuredData | undefined;

    if ("structuredData" in page.data) {
      structuredData = page.data.structuredData as StructuredData;
    } else if ("load" in page.data && typeof page.data.load === "function") {
      structuredData = (await page.data.load()).structuredData;
    }

    if (!structuredData)
      throw new Error(
        "Cannot find structured data from page, please define the page to index function."
      );

    const pageTree = source.getPageTree(page.locale);
    const path = findPath(
      pageTree.children,
      (node) => node.type === "page" && node.url === page.url
    );
    if (path) {
      breadcrumbs = [];
      path.pop();

      if (isBreadcrumbItem(pageTree.name)) {
        breadcrumbs.push(pageTree.name);
      }

      for (const segment of path) {
        if (!isBreadcrumbItem(segment.name)) continue;

        breadcrumbs.push(segment.name);
      }
    }

    return {
      title:
        page.data.title ??
        PathUtils.basename(page.path, PathUtils.extname(page.path)),
      breadcrumbs,
      description: page.data.description,
      url: page.url,
      id: page.url,
      structuredData,
      tag: tag?.(page.url)
    };
  };
}

interface Options<C extends LoaderConfig>
  extends Omit<AdvancedOptions, "indexes"> {
  localeMap?: {
    [K in C["i18n"] extends I18nConfig<infer Languages> ? Languages : string]?:
      | Partial<AdvancedOptions>
      | Language;
  };
  buildIndex?: (
    page: Page<C["source"]["pageData"]>
  ) => Awaitable<AdvancedIndex>;
  tag?: (pageUrl: string) => string;
}

export function createFromSource<C extends LoaderConfig>(
  source: LoaderOutput<C>,
  options?: Options<C>
): SearchAPI;

export function createFromSource<C extends LoaderConfig>(
  source: LoaderOutput<C>,
  options: Options<C> = {}
): SearchAPI {
  const { buildIndex = defaultBuildIndex(source, options.tag) } = options;

  if (source._i18n) {
    return createI18nSearchAPI("advanced", {
      ...options,
      i18n: source._i18n,
      indexes: async () => {
        const indexes = source.getLanguages().flatMap((entry) => {
          return entry.pages.map(async (page) => ({
            ...(await buildIndex(page)),
            locale: entry.language
          }));
        });

        return Promise.all(indexes);
      }
    });
  }

  return createSearchAPI("advanced", {
    ...options,
    indexes: async () => {
      const indexes = source.getPages().map((page) => buildIndex(page));

      return Promise.all(indexes);
    }
  });
}
