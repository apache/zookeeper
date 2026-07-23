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

import type * as PageTree from "fumadocs-core/page-tree";
import type { ReactNode } from "react";

export interface SidebarTab {
  /**
   * Redirect URL of the folder, usually the index page
   */
  url: string;

  icon?: ReactNode;
  title: ReactNode;
  description?: ReactNode;

  /**
   * Detect from a list of urls
   */
  urls?: Set<string>;
  unlisted?: boolean;
}

export interface GetSidebarTabsOptions {
  transform?: (option: SidebarTab, node: PageTree.Folder) => SidebarTab | null;
}

const defaultTransform: GetSidebarTabsOptions["transform"] = (option, node) => {
  if (!node.icon) return option;

  return {
    ...option,
    icon: (
      <div className="max-md:bg-fd-secondary size-full max-md:rounded-md max-md:border max-md:p-1.5 [&_svg]:size-full">
        {node.icon}
      </div>
    )
  };
};

export function getSidebarTabs(
  tree: PageTree.Root,
  { transform = defaultTransform }: GetSidebarTabsOptions = {}
): SidebarTab[] {
  const results: SidebarTab[] = [];

  function scanOptions(
    node: PageTree.Root | PageTree.Folder,
    unlisted?: boolean
  ) {
    if ("root" in node && node.root) {
      const urls = getFolderUrls(node);

      if (urls.size > 0) {
        const option: SidebarTab = {
          url: urls.values().next().value ?? "",
          title: node.name,
          icon: node.icon,
          unlisted,
          description: node.description,
          urls
        };

        const mapped = transform ? transform(option, node) : option;
        if (mapped) results.push(mapped);
      }
    }

    for (const child of node.children) {
      if (child.type === "folder") scanOptions(child, unlisted);
    }
  }

  scanOptions(tree);
  if (tree.fallback) scanOptions(tree.fallback, true);

  return results;
}

function getFolderUrls(
  folder: PageTree.Folder,
  output: Set<string> = new Set()
): Set<string> {
  if (folder.index) output.add(folder.index.url);

  for (const child of folder.children) {
    if (child.type === "page" && !child.external) output.add(child.url);
    if (child.type === "folder") getFolderUrls(child, output);
  }

  return output;
}
