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

import type { ComponentProps, ReactNode } from "react";
import { usePathname } from "fumadocs-core/framework";
import { isActive } from "../../../lib/urls";
import Link from "fumadocs-core/link";

interface Filterable {
  /**
   * Restrict where the item is displayed
   *
   * @defaultValue 'all'
   */
  on?: "menu" | "nav" | "all";
}

interface WithHref {
  url: string;
  /**
   * When the item is marked as active
   *
   * @defaultValue 'url'
   */
  active?: "url" | "nested-url" | "none";
  external?: boolean;
}

export interface MainItemType extends WithHref, Filterable {
  type?: "main";
  icon?: ReactNode;
  text: ReactNode;
  description?: ReactNode;
}

export interface IconItemType extends WithHref, Filterable {
  type: "icon";
  /**
   * `aria-label` of icon button
   */
  label?: string;
  icon: ReactNode;
  text: ReactNode;
  /**
   * @defaultValue true
   */
  secondary?: boolean;
}

export interface ButtonItemType extends WithHref, Filterable {
  type: "button";
  icon?: ReactNode;
  text: ReactNode;
  /**
   * @defaultValue false
   */
  secondary?: boolean;
}

export interface MenuItemType extends Partial<WithHref>, Filterable {
  type: "menu";
  icon?: ReactNode;
  text: ReactNode;

  items: (
    | (MainItemType & {
        /**
         * Options when displayed on navigation menu
         */
        menu?: ComponentProps<"a"> & {
          banner?: ReactNode;
        };
      })
    | CustomItemType
  )[];

  /**
   * @defaultValue false
   */
  secondary?: boolean;
}

export interface CustomItemType extends Filterable {
  type: "custom";
  /**
   * @defaultValue false
   */
  secondary?: boolean;
  children: ReactNode;
}

export type LinkItemType =
  | MainItemType
  | IconItemType
  | ButtonItemType
  | MenuItemType
  | CustomItemType;

export function LinkItem({
  ref,
  item,
  ...props
}: Omit<ComponentProps<"a">, "href"> & { item: WithHref }) {
  const pathname = usePathname();
  const activeType = item.active ?? "url";
  const active =
    activeType !== "none" &&
    isActive(item.url, pathname, activeType === "nested-url");

  return (
    <Link
      ref={ref}
      href={item.url}
      external={item.external}
      {...props}
      data-active={active}
    >
      {props.children}
    </Link>
  );
}
