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

import * as Base from "../sidebar/base";
import { type ComponentProps, useRef } from "react";
import { cva } from "class-variance-authority";
import { createPageTreeRenderer } from "../sidebar/page-tree";
import { createLinkItemRenderer } from "../sidebar/link-item";
import { buttonVariants } from "../../../../ui/button";
import { SearchToggle } from "../search-toggle";
import { Sidebar as SidebarIcon } from "lucide-react";
import { cn, mergeRefs } from "@/lib/utils";

const itemVariants = cva(
  "relative flex flex-row items-center gap-2 rounded-lg p-2 text-start text-fd-muted-foreground wrap-anywhere [&_svg]:size-4 [&_svg]:shrink-0",
  {
    variants: {
      variant: {
        link: "transition-colors hover:bg-fd-accent/50 hover:text-fd-accent-foreground/80 hover:transition-none data-[active=true]:bg-fd-primary/10 data-[active=true]:text-fd-primary data-[active=true]:hover:transition-colors",
        button:
          "transition-colors hover:bg-fd-accent/50 hover:text-fd-accent-foreground/80 hover:transition-none"
      },
      highlight: {
        true: "data-[active=true]:before:content-[''] data-[active=true]:before:bg-fd-primary data-[active=true]:before:absolute data-[active=true]:before:w-px data-[active=true]:before:inset-y-2.5 data-[active=true]:before:start-2.5"
      }
    }
  }
);

function getItemOffset(depth: number) {
  return `calc(${2 + 3 * depth} * var(--spacing))`;
}

export {
  SidebarProvider as Sidebar,
  SidebarFolder,
  SidebarCollapseTrigger,
  SidebarViewport,
  SidebarTrigger
} from "../sidebar/base";

export function SidebarContent({
  ref: refProp,
  className,
  children,
  isSearchToggleEnabled = true,
  ...props
}: ComponentProps<"aside"> & { isSearchToggleEnabled?: boolean }) {
  const ref = useRef<HTMLElement>(null);

  return (
    <Base.SidebarContent>
      {({ collapsed, hovered, ref: asideRef, ...rest }) => (
        <>
          <div
            data-sidebar-placeholder=""
            className="pointer-events-none sticky top-(--fd-docs-row-1) z-20 h-[calc(var(--fd-docs-height)-var(--fd-docs-row-1))] [grid-area:sidebar] *:pointer-events-auto max-md:hidden"
          >
            {collapsed && (
              <div className="absolute inset-y-0 start-0 w-4" {...rest} />
            )}
            <aside
              id="nd-sidebar"
              ref={mergeRefs(ref, refProp, asideRef)}
              data-collapsed={collapsed}
              data-hovered={collapsed && hovered}
              className={cn(
                "bg-fd-card absolute inset-y-0 start-0 flex flex-col items-end border-e text-sm duration-250 *:w-(--fd-sidebar-width)",
                collapsed && [
                  "inset-y-2 w-(--fd-sidebar-width) rounded-xl border transition-transform",
                  hovered
                    ? "translate-x-2 shadow-lg rtl:-translate-x-2"
                    : "-translate-x-(--fd-sidebar-width) rtl:translate-x-full"
                ],
                ref.current &&
                  (ref.current.getAttribute("data-collapsed") === "true") !==
                    collapsed &&
                  "transition-[width,inset-block,translate,background-color]",
                className
              )}
              {...props}
              {...rest}
            >
              {children}
            </aside>
          </div>
          <div
            data-sidebar-panel=""
            className={cn(
              "bg-fd-muted text-fd-muted-foreground fixed start-4 top-[calc(--spacing(4)+var(--fd-docs-row-3))] z-10 flex rounded-xl border p-0.5 shadow-lg transition-opacity",
              (!collapsed || hovered) && "pointer-events-none opacity-0"
            )}
          >
            <Base.SidebarCollapseTrigger
              className={cn(
                buttonVariants({
                  variant: "ghost",
                  size: "icon-sm",
                  className: "rounded-lg"
                })
              )}
            >
              <SidebarIcon />
            </Base.SidebarCollapseTrigger>
            {isSearchToggleEnabled && (
              <SearchToggle className="rounded-lg" hideIfDisabled />
            )}
          </div>
        </>
      )}
    </Base.SidebarContent>
  );
}

export function SidebarDrawer({
  children,
  className,
  ...props
}: ComponentProps<typeof Base.SidebarDrawerContent>) {
  return (
    <>
      <Base.SidebarDrawerOverlay className="data-[state=open]:animate-fd-fade-in data-[state=closed]:animate-fd-fade-out fixed inset-0 z-40 backdrop-blur-xs" />
      <Base.SidebarDrawerContent
        className={cn(
          "bg-fd-background data-[state=open]:animate-fd-sidebar-in data-[state=closed]:animate-fd-sidebar-out fixed inset-y-0 end-0 z-40 flex w-[85%] max-w-[380px] flex-col border-s text-[0.9375rem] shadow-lg",
          className
        )}
        {...props}
      >
        {children}
      </Base.SidebarDrawerContent>
    </>
  );
}

export function SidebarSeparator({
  className,
  style,
  children,
  ...props
}: ComponentProps<"p">) {
  const depth = Base.useFolderDepth();

  return (
    <Base.SidebarSeparator
      className={cn("[&_svg]:size-4 [&_svg]:shrink-0", className)}
      style={{
        paddingInlineStart: getItemOffset(depth),
        ...style
      }}
      {...props}
    >
      {children}
    </Base.SidebarSeparator>
  );
}

export function SidebarItem({
  className,
  style,
  children,
  ...props
}: ComponentProps<typeof Base.SidebarItem>) {
  const depth = Base.useFolderDepth();

  return (
    <Base.SidebarItem
      className={cn(
        itemVariants({ variant: "link", highlight: depth >= 1 }),
        className
      )}
      style={{
        paddingInlineStart: getItemOffset(depth),
        ...style
      }}
      {...props}
    >
      {children}
    </Base.SidebarItem>
  );
}

export function SidebarFolderTrigger({
  className,
  style,
  ...props
}: ComponentProps<typeof Base.SidebarFolderTrigger>) {
  const { depth, collapsible } = Base.useFolder()!;

  return (
    <Base.SidebarFolderTrigger
      className={cn(
        itemVariants({ variant: collapsible ? "button" : null }),
        "w-full",
        className
      )}
      style={{
        paddingInlineStart: getItemOffset(depth - 1),
        ...style
      }}
      {...props}
    >
      {props.children}
    </Base.SidebarFolderTrigger>
  );
}

export function SidebarFolderLink({
  className,
  style,
  ...props
}: ComponentProps<typeof Base.SidebarFolderLink>) {
  const depth = Base.useFolderDepth();

  return (
    <Base.SidebarFolderLink
      className={cn(
        itemVariants({ variant: "link", highlight: depth > 1 }),
        "w-full",
        className
      )}
      style={{
        paddingInlineStart: getItemOffset(depth - 1),
        ...style
      }}
      {...props}
    >
      {props.children}
    </Base.SidebarFolderLink>
  );
}

export function SidebarFolderContent({
  className,
  children,
  ...props
}: ComponentProps<typeof Base.SidebarFolderContent>) {
  const depth = Base.useFolderDepth();

  return (
    <Base.SidebarFolderContent
      className={cn(
        "relative",
        depth === 1 &&
          "before:bg-fd-border before:absolute before:inset-y-1 before:start-2.5 before:w-px before:content-['']",
        className
      )}
      {...props}
    >
      {children}
    </Base.SidebarFolderContent>
  );
}

export const SidebarPageTree = createPageTreeRenderer({
  SidebarFolder: Base.SidebarFolder,
  SidebarFolderContent,
  SidebarFolderLink,
  SidebarFolderTrigger,
  SidebarItem,
  SidebarSeparator
});

export const SidebarLinkItem = createLinkItemRenderer({
  SidebarFolder: Base.SidebarFolder,
  SidebarFolderContent,
  SidebarFolderLink,
  SidebarFolderTrigger,
  SidebarItem
});
