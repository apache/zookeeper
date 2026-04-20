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

import * as Primitive from "fumadocs-core/toc";
import { type ComponentProps, useEffect, useRef, useState } from "react";
import { TocThumb, useTOCItems } from "./index";
import { useI18n } from "fumadocs-ui/contexts/i18n";
import { cn, mergeRefs } from "@/lib/utils";

export function TOCItems({ ref, className, ...props }: ComponentProps<"div">) {
  const containerRef = useRef<HTMLDivElement>(null);
  const items = useTOCItems();
  const { text } = useI18n();

  const [svg, setSvg] = useState<{
    path: string;
    width: number;
    height: number;
  }>();

  useEffect(() => {
    if (!containerRef.current) return;
    const container = containerRef.current;

    function onResize(): void {
      if (container.clientHeight === 0) return;
      let w = 0,
        h = 0;
      const d: string[] = [];
      for (let i = 0; i < items.length; i++) {
        const element: HTMLElement | null = container.querySelector(
          `a[href="#${items[i].url.slice(1)}"]`
        );
        if (!element) continue;

        const styles = getComputedStyle(element);
        const offset = getLineOffset(items[i].depth) + 1,
          top = element.offsetTop + parseFloat(styles.paddingTop),
          bottom =
            element.offsetTop +
            element.clientHeight -
            parseFloat(styles.paddingBottom);

        w = Math.max(offset, w);
        h = Math.max(h, bottom);

        d.push(`${i === 0 ? "M" : "L"}${offset} ${top}`);
        d.push(`L${offset} ${bottom}`);
      }

      setSvg({
        path: d.join(" "),
        width: w + 1,
        height: h
      });
    }

    const observer = new ResizeObserver(onResize);
    onResize();

    observer.observe(container);
    return () => {
      observer.disconnect();
    };
  }, [items]);

  if (items.length === 0)
    return (
      <div className="bg-fd-card text-fd-muted-foreground rounded-lg border p-3 text-xs">
        {text.tocNoHeadings}
      </div>
    );

  return (
    <>
      {svg && (
        <div
          className="absolute start-0 top-0 rtl:-scale-x-100"
          style={{
            width: svg.width,
            height: svg.height,
            maskImage: `url("data:image/svg+xml,${
              // Inline SVG
              encodeURIComponent(
                `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${svg.width} ${svg.height}"><path d="${svg.path}" stroke="black" stroke-width="1" fill="none" /></svg>`
              )
            }")`
          }}
        >
          <TocThumb
            containerRef={containerRef}
            className="bg-fd-primary absolute top-(--fd-top) h-(--fd-height) w-full transition-[top,height]"
          />
        </div>
      )}
      <div
        ref={mergeRefs(containerRef, ref)}
        className={cn("flex flex-col", className)}
        {...props}
      >
        {items.map((item, i) => (
          <TOCItem
            key={item.url}
            item={item}
            upper={items[i - 1]?.depth}
            lower={items[i + 1]?.depth}
          />
        ))}
      </div>
    </>
  );
}

function getItemOffset(depth: number): number {
  if (depth <= 2) return 14;
  if (depth === 3) return 26;
  return 36;
}

function getLineOffset(depth: number): number {
  return depth >= 3 ? 10 : 0;
}

function TOCItem({
  item,
  upper = item.depth,
  lower = item.depth
}: {
  item: Primitive.TOCItemType;
  upper?: number;
  lower?: number;
}) {
  const offset = getLineOffset(item.depth),
    upperOffset = getLineOffset(upper),
    lowerOffset = getLineOffset(lower);

  return (
    <Primitive.TOCItem
      href={item.url}
      style={{
        paddingInlineStart: getItemOffset(item.depth)
      }}
      className="prose text-fd-muted-foreground hover:text-fd-accent-foreground data-[active=true]:text-fd-primary relative py-1.5 text-sm wrap-anywhere transition-colors first:pt-0 last:pb-0"
    >
      {offset !== upperOffset && (
        <svg
          xmlns="http://www.w3.org/2000/svg"
          viewBox="0 0 16 16"
          className="absolute start-0 -top-1.5 size-4 rtl:-scale-x-100"
        >
          <line
            x1={upperOffset}
            y1="0"
            x2={offset}
            y2="12"
            className="stroke-fd-foreground/10"
            strokeWidth="1"
          />
        </svg>
      )}
      <div
        className={cn(
          "bg-fd-foreground/10 absolute inset-y-0 w-px",
          offset !== upperOffset && "top-1.5",
          offset !== lowerOffset && "bottom-1.5"
        )}
        style={{
          insetInlineStart: offset
        }}
      />
      {item.title}
    </Primitive.TOCItem>
  );
}
