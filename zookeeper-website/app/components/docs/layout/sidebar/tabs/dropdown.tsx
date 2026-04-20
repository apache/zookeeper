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

import { Check, ChevronsUpDown } from "lucide-react";
import { type ComponentProps, type ReactNode, useMemo, useState } from "react";
import Link from "fumadocs-core/link";
import { usePathname } from "fumadocs-core/framework";
import { isActive, normalize } from "@/lib/urls";
import { useSidebar } from "../base";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import type { SidebarTab } from "./index";
import { cn } from "@/lib/utils";

export interface SidebarTabWithProps extends SidebarTab {
  props?: ComponentProps<"a">;
}

export function SidebarTabsDropdown({
  options,
  placeholder,
  ...props
}: {
  placeholder?: ReactNode;
  options: SidebarTabWithProps[];
} & ComponentProps<"button">) {
  const [open, setOpen] = useState(false);
  const { closeOnRedirect } = useSidebar();
  const pathname = usePathname();

  const selected = useMemo(() => {
    return options.findLast((item) => isTabActive(item, pathname));
  }, [options, pathname]);

  const onClick = () => {
    closeOnRedirect.current = false;
    setOpen(false);
  };

  const item = selected ? (
    <>
      <div className="size-9 shrink-0 empty:hidden md:size-5">
        {selected.icon}
      </div>
      <div>
        <p className="text-sm font-medium">{selected.title}</p>
        <p className="text-fd-muted-foreground text-sm empty:hidden md:hidden">
          {selected.description}
        </p>
      </div>
    </>
  ) : (
    placeholder
  );

  return (
    <Popover open={open} onOpenChange={setOpen}>
      {item && (
        <PopoverTrigger
          {...props}
          className={cn(
            "bg-fd-secondary/50 text-fd-secondary-foreground hover:bg-fd-accent data-[state=open]:bg-fd-accent data-[state=open]:text-fd-accent-foreground flex items-center gap-2 rounded-lg border p-2 text-start transition-colors",
            props.className
          )}
        >
          {item}
          <ChevronsUpDown className="text-fd-muted-foreground ms-auto size-4 shrink-0" />
        </PopoverTrigger>
      )}
      <PopoverContent className="fd-scroll-container flex w-(--radix-popover-trigger-width) flex-col gap-1 p-1">
        {options.map((item) => {
          const isActive = selected && item.url === selected.url;
          if (!isActive && item.unlisted) return;

          return (
            <Link
              key={item.url}
              href={item.url}
              onClick={onClick}
              {...item.props}
              className={cn(
                "hover:bg-fd-accent hover:text-fd-accent-foreground flex items-center gap-2 rounded-lg p-1.5",
                item.props?.className
              )}
            >
              <div className="size-9 shrink-0 empty:hidden md:mb-auto md:size-5">
                {item.icon}
              </div>
              <div>
                <p className="text-sm leading-none font-medium">{item.title}</p>
                <p className="text-fd-muted-foreground mt-1 text-[0.8125rem] empty:hidden">
                  {item.description}
                </p>
              </div>

              <Check
                className={cn(
                  "text-fd-primary ms-auto size-3.5 shrink-0",
                  !isActive && "invisible"
                )}
              />
            </Link>
          );
        })}
      </PopoverContent>
    </Popover>
  );
}

export function isTabActive(tab: SidebarTab, pathname: string) {
  if (tab.urls) return tab.urls.has(normalize(pathname));

  return isActive(tab.url, pathname, true);
}
