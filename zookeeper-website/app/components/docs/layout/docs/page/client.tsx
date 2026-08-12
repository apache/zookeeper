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
  Fragment,
  use,
  useEffect,
  useEffectEvent,
  useMemo,
  useRef,
  useState
} from "react";
import { ChevronDown, ChevronLeft, ChevronRight } from "lucide-react";
import Link from "fumadocs-core/link";
import { useI18n } from "fumadocs-ui/contexts/i18n";
import { useTreeContext, useTreePath } from "fumadocs-ui/contexts/tree";
import type * as PageTree from "fumadocs-core/page-tree";
import { usePathname } from "fumadocs-core/framework";
import {
  type BreadcrumbOptions,
  getBreadcrumbItemsFromPath
} from "fumadocs-core/breadcrumb";
import { isActive } from "../../../../../lib/urls";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger
} from "../../../../../ui/collapsible";
import { useTOCItems } from "../../../toc";
import { useActiveAnchor } from "fumadocs-core/toc";
import { LayoutContext } from "../client";
import { cn } from "@/lib/utils";

const TocPopoverContext = createContext<{
  open: boolean;
  setOpen: (open: boolean) => void;
} | null>(null);

export function PageTOCPopover({
  className,
  children,
  ...rest
}: ComponentProps<"div">) {
  const ref = useRef<HTMLElement>(null);
  const [open, setOpen] = useState(false);
  const { isNavTransparent } = use(LayoutContext)!;

  const onClick = useEffectEvent((e: Event) => {
    if (!open) return;

    if (ref.current && !ref.current.contains(e.target as HTMLElement))
      setOpen(false);
  });

  useEffect(() => {
    window.addEventListener("click", onClick);

    return () => {
      window.removeEventListener("click", onClick);
    };
  }, [onClick]);

  return (
    <TocPopoverContext
      value={useMemo(
        () => ({
          open,
          setOpen
        }),
        [setOpen, open]
      )}
    >
      <Collapsible
        open={open}
        onOpenChange={setOpen}
        data-toc-popover=""
        className={cn(
          "sticky top-(--fd-docs-row-2) z-10 h-(--fd-toc-popover-height) [grid-area:toc-popover] xl:hidden",
          className
        )}
        {...rest}
      >
        <header
          ref={ref}
          className={cn(
            "border-b backdrop-blur-sm transition-colors",
            (!isNavTransparent || open) && "bg-fd-background/80",
            open && "shadow-lg"
          )}
        >
          {children}
        </header>
      </Collapsible>
    </TocPopoverContext>
  );
}

export function PageTOCPopoverTrigger({
  className,
  ...props
}: ComponentProps<"button">) {
  const { text } = useI18n();
  const { open } = use(TocPopoverContext)!;
  const items = useTOCItems();
  const active = useActiveAnchor();
  const selected = useMemo(
    () => items.findIndex((item) => active === item.url.slice(1)),
    [items, active]
  );
  const path = useTreePath().at(-1);
  const showItem = selected !== -1 && !open;

  return (
    <CollapsibleTrigger
      className={cn(
        "text-fd-muted-foreground flex h-10 w-full items-center gap-2.5 px-4 py-2.5 text-start text-sm focus-visible:outline-none md:px-6 [&_svg]:size-4",
        className
      )}
      data-toc-popover-trigger=""
      {...props}
    >
      <ProgressCircle
        value={(selected + 1) / Math.max(1, items.length)}
        max={1}
        className={cn("shrink-0", open && "text-fd-primary")}
      />
      <span className="grid flex-1 *:col-start-1 *:row-start-1 *:my-auto">
        <span
          className={cn(
            "truncate transition-[opacity,translate,color]",
            open && "text-fd-foreground",
            showItem && "pointer-events-none -translate-y-full opacity-0"
          )}
        >
          {path?.name ?? text.toc}
        </span>
        <span
          className={cn(
            "truncate transition-[opacity,translate]",
            !showItem && "pointer-events-none translate-y-full opacity-0"
          )}
        >
          {items[selected]?.title}
        </span>
      </span>
      <ChevronDown
        className={cn(
          "mx-0.5 shrink-0 transition-transform",
          open && "rotate-180"
        )}
      />
    </CollapsibleTrigger>
  );
}

interface ProgressCircleProps
  extends Omit<React.ComponentProps<"svg">, "strokeWidth"> {
  value: number;
  strokeWidth?: number;
  size?: number;
  min?: number;
  max?: number;
}

function clamp(input: number, min: number, max: number): number {
  if (input < min) return min;
  if (input > max) return max;
  return input;
}

function ProgressCircle({
  value,
  strokeWidth = 2,
  size = 24,
  min = 0,
  max = 100,
  ...restSvgProps
}: ProgressCircleProps) {
  const normalizedValue = clamp(value, min, max);
  const radius = (size - strokeWidth) / 2;
  const circumference = 2 * Math.PI * radius;
  const progress = (normalizedValue / max) * circumference;
  const circleProps = {
    cx: size / 2,
    cy: size / 2,
    r: radius,
    fill: "none",
    strokeWidth
  };

  return (
    <svg
      role="progressbar"
      viewBox={`0 0 ${size} ${size}`}
      aria-valuenow={normalizedValue}
      aria-valuemin={min}
      aria-valuemax={max}
      {...restSvgProps}
    >
      <circle {...circleProps} className="stroke-current/25" />
      <circle
        {...circleProps}
        stroke="currentColor"
        strokeDasharray={circumference}
        strokeDashoffset={circumference - progress}
        strokeLinecap="round"
        transform={`rotate(-90 ${size / 2} ${size / 2})`}
        className="transition-all"
      />
    </svg>
  );
}

export function PageTOCPopoverContent(props: ComponentProps<"div">) {
  return (
    <CollapsibleContent
      data-toc-popover-content=""
      {...props}
      className={cn("flex max-h-[50vh] flex-col px-4 md:px-6", props.className)}
    >
      {props.children}
    </CollapsibleContent>
  );
}

export function PageLastUpdate({
  date: value,
  ...props
}: Omit<ComponentProps<"p">, "children"> & { date: Date }) {
  const { text } = useI18n();
  const [date, setDate] = useState("");

  useEffect(() => {
    // to the timezone of client
    setDate(value.toLocaleDateString());
  }, [value]);

  return (
    <p
      {...props}
      className={cn("text-fd-muted-foreground text-sm", props.className)}
    >
      {text.lastUpdate} {date}
    </p>
  );
}

type Item = Pick<PageTree.Item, "name" | "description" | "url">;
export interface FooterProps extends ComponentProps<"div"> {
  /**
   * Items including information for the next and previous page
   */
  items?: {
    previous?: Item;
    next?: Item;
  };
}

export function PageFooter({
  items,
  children,
  className,
  ...props
}: FooterProps) {
  const footerList = useFooterItems();
  const pathname = usePathname();
  const { previous, next } = useMemo(() => {
    if (items) return items;

    const idx = footerList.findIndex((item) =>
      isActive(item.url, pathname, false)
    );

    if (idx === -1) return {};
    return {
      previous: footerList[idx - 1],
      next: footerList[idx + 1]
    };
  }, [footerList, items, pathname]);

  return (
    <>
      <div
        className={cn(
          "@container grid gap-4",
          previous && next ? "grid-cols-2" : "grid-cols-1",
          className
        )}
        {...props}
      >
        {previous && <FooterItem item={previous} index={0} />}
        {next && <FooterItem item={next} index={1} />}
      </div>
      {children}
    </>
  );
}

function FooterItem({ item, index }: { item: Item; index: 0 | 1 }) {
  const { text } = useI18n();
  const Icon = index === 0 ? ChevronLeft : ChevronRight;

  return (
    <Link
      href={item.url}
      className={cn(
        "hover:bg-fd-accent/80 hover:text-fd-accent-foreground flex flex-col gap-2 rounded-lg border p-4 text-sm transition-colors @max-lg:col-span-full",
        index === 1 && "text-end"
      )}
    >
      <div
        className={cn(
          "inline-flex items-center gap-1.5 font-medium",
          index === 1 && "flex-row-reverse"
        )}
      >
        <Icon className="-mx-1 size-4 shrink-0 rtl:rotate-180" />
        <p>{item.name}</p>
      </div>
      <p className="text-fd-muted-foreground truncate">
        {item.description ?? (index === 0 ? text.previousPage : text.nextPage)}
      </p>
    </Link>
  );
}

export type BreadcrumbProps = BreadcrumbOptions & ComponentProps<"div">;

export function PageBreadcrumb({
  includeRoot,
  includeSeparator,
  includePage,
  ...props
}: BreadcrumbProps) {
  const path = useTreePath();
  const { root } = useTreeContext();
  const items = useMemo(() => {
    return getBreadcrumbItemsFromPath(root, path, {
      includePage,
      includeSeparator,
      includeRoot
    });
  }, [includePage, includeRoot, includeSeparator, path, root]);

  if (items.length === 0) return null;

  return (
    <div
      {...props}
      className={cn(
        "text-fd-muted-foreground flex items-center gap-1.5 text-sm",
        props.className
      )}
    >
      {items.map((item, i) => {
        const className = cn(
          "truncate",
          i === items.length - 1 && "text-fd-primary font-medium"
        );

        return (
          <Fragment key={i}>
            {i !== 0 && <ChevronRight className="size-3.5 shrink-0" />}
            {item.url ? (
              <Link
                href={item.url}
                className={cn(className, "transition-opacity hover:opacity-80")}
              >
                {item.name}
              </Link>
            ) : (
              <span className={className}>{item.name}</span>
            )}
          </Fragment>
        );
      })}
    </div>
  );
}

const footerCache = new Map<string, PageTree.Item[]>();

/**
 * @returns a list of page tree items (linear), that you can obtain footer items
 */
export function useFooterItems(): PageTree.Item[] {
  const { root } = useTreeContext();
  const cached = footerCache.get(root.$id);
  if (cached) return cached;

  const list: PageTree.Item[] = [];
  function onNode(node: PageTree.Node) {
    if (node.type === "folder") {
      if (node.index) onNode(node.index);
      for (const child of node.children) onNode(child);
    } else if (node.type === "page" && !node.external) {
      list.push(node);
    }
  }

  for (const child of root.children) onNode(child);
  footerCache.set(root.$id, list);
  return list;
}
