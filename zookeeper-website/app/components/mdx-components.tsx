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

import type { MDXComponents } from "mdx/types";
import { ExternalLinkIcon } from "lucide-react";
import defaultMdxComponents from "fumadocs-ui/mdx";
import { cn } from "@/lib/utils";
import { Link } from "./link";

export function getMDXComponents(overrides?: MDXComponents): MDXComponents {
  return {
    ...defaultMdxComponents,
    h1: (props) => (
      <h1
        className="mb-12 text-center text-4xl font-semibold tracking-tight text-balance md:text-6xl"
        {...props}
      />
    ),
    h2: (props) => (
      <h2
        className="mt-12 mb-4 scroll-mt-28 text-3xl font-semibold tracking-tight md:text-4xl"
        {...props}
      />
    ),
    h3: (props) => (
      <h3
        className="mt-8 mb-1 scroll-mt-28 text-xl font-semibold tracking-tight"
        {...props}
      />
    ),
    p: (props) => (
      <p className="mb-4 text-base leading-7 wrap-anywhere" {...props} />
    ),
    ol: (props) => (
      <ol className="mb-4 ml-6 list-decimal space-y-2" {...props} />
    ),
    ul: (props) => <ul className="mb-4 ml-6 list-disc space-y-2" {...props} />,
    li: (props) => <li className="leading-7" {...props} />,

    a: ({ href, children, ...rest }) => {
      const isExternal =
        href?.startsWith("http") &&
        !href?.startsWith("https://zookeeper.apache.org/");
      if (isExternal) {
        const onlyImg = Array.isArray(children)
          ? children.every(
              (c: any) => c?.type === "img" || typeof c === "object"
            )
          : typeof children === "object";
        return (
          <a
            href={href}
            target="_blank"
            rel="noopener noreferrer"
            className={
              onlyImg
                ? "inline-block"
                : "text-primary inline font-normal no-underline decoration-0 underline-offset-4 hover:underline hover:opacity-100"
            }
            {...rest}
          >
            {children}
            {!onlyImg && (
              <>
                {"\u00A0"}
                <ExternalLinkIcon className="inline size-3.5 align-[-2px]" />
              </>
            )}
          </a>
        );
      }
      return (
        <Link
          to={href ?? "#"}
          className="text-primary font-normal no-underline decoration-1 underline-offset-4 hover:underline hover:opacity-100"
          {...(rest as any)}
        >
          {children}
        </Link>
      );
    },
    blockquote: (props) => (
      <blockquote
        className="border-border my-6 border-l-4 pl-6 italic"
        {...props}
      />
    ),
    img: ({ src = "", alt = "", ...rest }) => (
      <img
        src={src}
        alt={alt}
        loading="lazy"
        className="my-6 max-w-full rounded-lg"
        {...rest}
      />
    ),
    table: (props) => (
      <div className="border-border my-8 w-full overflow-x-auto rounded-lg border">
        <table className="!my-0 w-full border-collapse text-sm" {...props} />
      </div>
    ),
    thead: (props) => <thead className="bg-muted" {...props} />,
    tr: (props) => (
      <tr
        className="border-border hover:bg-muted/50 border-b transition-colors"
        {...props}
      />
    ),
    th: (props) => (
      <th
        className="border-none px-4 py-3 text-left font-semibold"
        {...props}
      />
    ),
    td: (props) => (
      <td className="border-none px-4 py-3 align-top" {...props} />
    ),

    ...overrides
  };
}

interface MdLayoutProps {
  className?: string;
  overrides?: MDXComponents;
  Content: any;
}

export function MdLayout({ className, overrides, Content }: MdLayoutProps) {
  return (
    <div className={cn("container mx-auto px-4 py-12", className)}>
      <article className="prose prose-slate dark:prose-invert">
        <Content components={getMDXComponents(overrides)} />
      </article>
    </div>
  );
}
