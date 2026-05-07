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
import {
  type ComponentProps,
  createContext,
  type RefObject,
  use,
  useEffect,
  useEffectEvent,
  useRef
} from "react";
import { useOnChange } from "fumadocs-core/utils/use-on-change";
import { cn, mergeRefs } from "@/lib/utils";

const TOCContext = createContext<Primitive.TOCItemType[]>([]);

export function useTOCItems(): Primitive.TOCItemType[] {
  return use(TOCContext);
}

export function TOCProvider({
  toc,
  children,
  ...props
}: ComponentProps<typeof Primitive.AnchorProvider>) {
  return (
    <TOCContext value={toc}>
      <Primitive.AnchorProvider toc={toc} {...props}>
        {children}
      </Primitive.AnchorProvider>
    </TOCContext>
  );
}

export function TOCScrollArea({
  ref,
  className,
  ...props
}: ComponentProps<"div">) {
  const viewRef = useRef<HTMLDivElement>(null);

  return (
    <div
      ref={mergeRefs(viewRef, ref)}
      className={cn(
        "relative ms-px min-h-0 overflow-auto mask-[linear-gradient(to_bottom,transparent,white_16px,white_calc(100%-16px),transparent)] py-3 text-sm [scrollbar-width:none]",
        className
      )}
      {...props}
    >
      <Primitive.ScrollProvider containerRef={viewRef}>
        {props.children}
      </Primitive.ScrollProvider>
    </div>
  );
}

type TocThumbType = [top: number, height: number];

interface RefProps {
  containerRef: RefObject<HTMLElement | null>;
}

export function TocThumb({
  containerRef,
  ...props
}: ComponentProps<"div"> & RefProps) {
  const thumbRef = useRef<HTMLDivElement>(null);
  const active = Primitive.useActiveAnchors();
  function update(info: TocThumbType): void {
    const element = thumbRef.current;
    if (!element) return;
    element.style.setProperty("--fd-top", `${info[0]}px`);
    element.style.setProperty("--fd-height", `${info[1]}px`);
  }

  const onPrint = useEffectEvent(() => {
    if (containerRef.current) {
      update(calc(containerRef.current, active));
    }
  });

  useEffect(() => {
    if (!containerRef.current) return;
    const container = containerRef.current;

    const observer = new ResizeObserver(onPrint);
    observer.observe(container);

    return () => {
      observer.disconnect();
    };
  }, [containerRef, onPrint]);

  useOnChange(active, () => {
    if (containerRef.current) {
      update(calc(containerRef.current, active));
    }
  });

  return <div ref={thumbRef} data-hidden={active.length === 0} {...props} />;
}

function calc(container: HTMLElement, active: string[]): TocThumbType {
  if (active.length === 0 || container.clientHeight === 0) {
    return [0, 0];
  }

  let upper = Number.MAX_VALUE,
    lower = 0;

  for (const item of active) {
    const element = container.querySelector<HTMLElement>(`a[href="#${item}"]`);
    if (!element) continue;

    const styles = getComputedStyle(element);
    upper = Math.min(upper, element.offsetTop + parseFloat(styles.paddingTop));
    lower = Math.max(
      lower,
      element.offsetTop +
        element.clientHeight -
        parseFloat(styles.paddingBottom)
    );
  }

  return [upper, lower - upper];
}
