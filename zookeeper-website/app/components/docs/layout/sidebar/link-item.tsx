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

import type { HTMLAttributes } from "react";
import type * as Base from "./base";
import type { LinkItemType } from "../link-item";

type InternalComponents = Pick<
  typeof Base,
  | "SidebarFolder"
  | "SidebarFolderLink"
  | "SidebarFolderContent"
  | "SidebarFolderTrigger"
  | "SidebarItem"
>;

export function createLinkItemRenderer({
  SidebarFolder,
  SidebarFolderContent,
  SidebarFolderLink,
  SidebarFolderTrigger,
  SidebarItem
}: InternalComponents) {
  /**
   * Render sidebar items from page tree
   */
  return function SidebarLinkItem({
    item,
    ...props
  }: HTMLAttributes<HTMLElement> & {
    item: Exclude<LinkItemType, { type: "icon" }>;
  }) {
    if (item.type === "custom") return <div {...props}>{item.children}</div>;

    if (item.type === "menu")
      return (
        <SidebarFolder {...props}>
          {item.url ? (
            <SidebarFolderLink href={item.url} external={item.external}>
              {item.icon}
              {item.text}
            </SidebarFolderLink>
          ) : (
            <SidebarFolderTrigger>
              {item.icon}
              {item.text}
            </SidebarFolderTrigger>
          )}
          <SidebarFolderContent>
            {item.items.map((child, i) => (
              <SidebarLinkItem key={i} item={child} />
            ))}
          </SidebarFolderContent>
        </SidebarFolder>
      );

    return (
      <SidebarItem
        href={item.url}
        icon={item.icon}
        external={item.external}
        {...props}
      >
        {item.text}
      </SidebarItem>
    );
  };
}
