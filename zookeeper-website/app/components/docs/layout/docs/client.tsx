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
  type ComponentProps,
  createContext,
  type ReactNode,
  use,
  useEffect,
  useMemo,
  useState
} from "react";
import { useSidebar } from "../sidebar/base";
import { usePathname } from "fumadocs-core/framework";
import Link from "fumadocs-core/link";
import type { SidebarTab } from "../sidebar/tabs";
import { isTabActive } from "../sidebar/tabs/dropdown";
import { cn } from "@/lib/utils";

export const LayoutContext = createContext<{
  isNavTransparent: boolean;
} | null>(null);

export function LayoutContextProvider({
  navTransparentMode = "none",
  children
}: {
  navTransparentMode?: "always" | "top" | "none";
  children: ReactNode;
}) {
  const isTop =
    useIsScrollTop({ enabled: navTransparentMode === "top" }) ?? true;
  const isNavTransparent =
    navTransparentMode === "top" ? isTop : navTransparentMode === "always";

  return (
    <LayoutContext
      value={useMemo(
        () => ({
          isNavTransparent
        }),
        [isNavTransparent]
      )}
    >
      {children}
    </LayoutContext>
  );
}

export function LayoutHeader(props: ComponentProps<"header">) {
  const { isNavTransparent } = use(LayoutContext)!;

  return (
    <header data-transparent={isNavTransparent} {...props}>
      {props.children}
    </header>
  );
}

export function LayoutBody({
  className,
  style,
  children,
  ...props
}: ComponentProps<"div">) {
  const { collapsed } = useSidebar();

  return (
    <div
      id="nd-docs-layout"
      className={cn(
        "grid min-h-(--fd-docs-height) auto-cols-auto auto-rows-auto overflow-x-clip transition-[grid-template-columns] [--fd-docs-height:100dvh] [--fd-header-height:0px] [--fd-sidebar-width:0px] [--fd-toc-popover-height:0px] [--fd-toc-width:285px] max-xl:[--fd-toc-popover-height:--spacing(10)] max-md:[--fd-header-height:--spacing(14)] md:[--fd-sidebar-width:285px]",
        className
      )}
      data-sidebar-collapsed={collapsed}
      style={
        {
          gridTemplate: `"sidebar header toc"
        "sidebar toc-popover toc"
        "sidebar main toc" 1fr / minmax(var(--fd-sidebar-col), 1fr) minmax(0, calc(var(--fd-layout-width,97rem) - var(--fd-sidebar-width) - var(--fd-toc-width))) minmax(min-content, 1fr)`,
          "--fd-docs-row-1": "var(--fd-banner-height, 0px)",
          "--fd-docs-row-2":
            "calc(var(--fd-docs-row-1) + var(--fd-header-height))",
          "--fd-docs-row-3":
            "calc(var(--fd-docs-row-2) + var(--fd-toc-popover-height))",
          "--fd-sidebar-col": collapsed ? "0px" : "var(--fd-sidebar-width)",
          ...style
        } as object
      }
      {...props}
    >
      {children}
    </div>
  );
}

export function LayoutTabs({
  options,
  ...props
}: ComponentProps<"div"> & {
  options: SidebarTab[];
}) {
  const pathname = usePathname();
  const selected = useMemo(() => {
    return options.findLast((option) => isTabActive(option, pathname));
  }, [options, pathname]);

  return (
    <div
      {...props}
      className={cn(
        "flex flex-row items-end gap-6 overflow-auto [grid-area:main]",
        props.className
      )}
    >
      {options.map((option, i) => (
        <Link
          key={i}
          href={option.url}
          className={cn(
            "text-fd-muted-foreground hover:text-fd-accent-foreground inline-flex items-center gap-2 border-b-2 border-transparent pb-1.5 text-sm font-medium text-nowrap transition-colors",
            option.unlisted && selected !== option && "hidden",
            selected === option && "border-fd-primary text-fd-primary"
          )}
        >
          {option.title}
        </Link>
      ))}
    </div>
  );
}

export function useIsScrollTop({ enabled = true }: { enabled?: boolean }) {
  const [isTop, setIsTop] = useState<boolean | undefined>();

  useEffect(() => {
    if (!enabled) return;

    const listener = () => {
      setIsTop(window.scrollY < 10);
    };

    listener();
    window.addEventListener("scroll", listener);
    return () => {
      window.removeEventListener("scroll", listener);
    };
  }, [enabled]);

  return isTop;
}
