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
import { buttonVariants } from "../../../../../ui/button";
import { Edit, Text } from "lucide-react";
import { I18nLabel } from "fumadocs-ui/contexts/i18n";
import {
  type BreadcrumbProps,
  type FooterProps,
  PageBreadcrumb,
  PageFooter,
  PageTOCPopover,
  PageTOCPopoverContent,
  PageTOCPopoverTrigger
} from "./client";
import type { AnchorProviderProps, TOCItemType } from "fumadocs-core/toc";
import * as TocDefault from "../../../toc/default";
import * as TocClerk from "../../../toc/clerk";
import { TOCProvider, TOCScrollArea } from "../../../toc";
import { cn } from "@/lib/utils";

interface BreadcrumbOptions extends BreadcrumbProps {
  enabled: boolean;
  component: ReactNode;
}

interface FooterOptions extends FooterProps {
  enabled: boolean;
  component: ReactNode;
}

export interface DocsPageProps {
  toc?: TOCItemType[];
  tableOfContent?: Partial<TableOfContentOptions>;
  tableOfContentPopover?: Partial<TableOfContentPopoverOptions>;

  /**
   * Extend the page to fill all available space
   *
   * @defaultValue false
   */
  full?: boolean;

  /**
   * Replace or disable breadcrumb
   */
  breadcrumb?: Partial<BreadcrumbOptions>;

  /**
   * Footer navigation, located under the page body.
   *
   * You can specify `footer.children` to add extra components under the footer.
   */
  footer?: Partial<FooterOptions>;

  children?: ReactNode;

  /**
   * Apply class names to the `#nd-page` container.
   */
  className?: string;
}

type TableOfContentOptions = Pick<AnchorProviderProps, "single"> & {
  /**
   * Custom content in TOC container, before the main TOC
   */
  header?: ReactNode;

  /**
   * Custom content in TOC container, after the main TOC
   */
  footer?: ReactNode;

  enabled: boolean;
  component: ReactNode;

  /**
   * @defaultValue 'normal'
   */
  style?: "normal" | "clerk";
};

type TableOfContentPopoverOptions = Omit<TableOfContentOptions, "single">;

export function DocsPage({
  breadcrumb: {
    enabled: breadcrumbEnabled = true,
    component: breadcrumb,
    ...breadcrumbProps
  } = {},
  footer: {
    enabled: footerEnabled,
    component: footerReplace,
    ...footerProps
  } = {},
  full = false,
  tableOfContentPopover: {
    enabled: tocPopoverEnabled,
    component: tocPopover,
    ...tocPopoverOptions
  } = {},
  tableOfContent: {
    enabled: tocEnabled,
    component: tocReplace,
    ...tocOptions
  } = {},
  toc = [],
  children,
  className
}: DocsPageProps) {
  // disable TOC on full mode, you can still enable it with `enabled` option.
  tocEnabled ??=
    !full &&
    (toc.length > 0 ||
      tocOptions.footer !== undefined ||
      tocOptions.header !== undefined);

  tocPopoverEnabled ??=
    toc.length > 0 ||
    tocPopoverOptions.header !== undefined ||
    tocPopoverOptions.footer !== undefined;

  let wrapper = (children: ReactNode) => children;

  if (tocEnabled || tocPopoverEnabled) {
    wrapper = (children) => (
      <TOCProvider single={tocOptions.single} toc={toc}>
        {children}
      </TOCProvider>
    );
  }

  return wrapper(
    <>
      {tocPopoverEnabled &&
        (tocPopover ?? (
          <PageTOCPopover>
            <PageTOCPopoverTrigger />
            <PageTOCPopoverContent>
              {tocPopoverOptions.header}
              <TOCScrollArea>
                {tocPopoverOptions.style === "clerk" ? (
                  <TocClerk.TOCItems />
                ) : (
                  <TocDefault.TOCItems />
                )}
              </TOCScrollArea>
              {tocPopoverOptions.footer}
            </PageTOCPopoverContent>
          </PageTOCPopover>
        ))}
      <article
        id="nd-page"
        data-full={full}
        className={cn(
          "mx-auto flex w-full max-w-[900px] flex-col gap-4 px-4 py-6 [grid-area:main] md:px-6 md:pt-8 xl:px-8 xl:pt-14",
          full ? "max-w-[1200px]" : "xl:[--fd-toc-width:285px]",
          className
        )}
      >
        {breadcrumbEnabled &&
          (breadcrumb ?? <PageBreadcrumb {...breadcrumbProps} />)}
        {children}
        {footerEnabled !== false &&
          (footerReplace ?? <PageFooter {...footerProps} />)}
      </article>
      {tocEnabled &&
        (tocReplace ?? (
          <div
            id="nd-toc"
            className="sticky top-(--fd-docs-row-1) flex h-[calc(var(--fd-docs-height)-var(--fd-docs-row-1))] w-(--fd-toc-width) flex-col justify-self-end pe-4 pt-12 pb-2 [grid-area:toc] max-xl:hidden"
          >
            {tocOptions.header}
            <h3
              id="toc-title"
              className="text-fd-muted-foreground inline-flex items-center gap-1.5 text-sm"
            >
              <Text className="size-4" />
              <I18nLabel label="toc" />
            </h3>
            <TOCScrollArea>
              {tocOptions.style === "clerk" ? (
                <TocClerk.TOCItems />
              ) : (
                <TocDefault.TOCItems />
              )}
            </TOCScrollArea>
            {tocOptions.footer}
          </div>
        ))}
    </>
  );
}

export function EditOnGitHub(props: ComponentProps<"a">) {
  return (
    <a
      target="_blank"
      rel="noreferrer noopener"
      {...props}
      className={cn(
        buttonVariants({
          variant: "secondary",
          size: "sm",
          className: "not-prose gap-1.5"
        }),
        props.className
      )}
    >
      {props.children ?? (
        <>
          <Edit className="size-3.5" />
          <I18nLabel label="editOnGithub" />
        </>
      )}
    </a>
  );
}

/**
 * Add typography styles
 */
export function DocsBody({
  children,
  className,
  ...props
}: ComponentProps<"div">) {
  return (
    <div {...props} className={cn("prose flex-1", className)}>
      {children}
    </div>
  );
}

export function DocsDescription({
  children,
  className,
  ...props
}: ComponentProps<"p">) {
  // Don't render if no description provided
  if (children === undefined) return null;

  return (
    <p
      {...props}
      className={cn("text-fd-muted-foreground mb-8 text-lg", className)}
    >
      {children}
    </p>
  );
}

export function DocsTitle({
  children,
  className,
  ...props
}: ComponentProps<"h1">) {
  return (
    <h1 {...props} className={cn("text-[1.75em] font-semibold", className)}>
      {children}
    </h1>
  );
}

export { PageLastUpdate, PageBreadcrumb } from "./client";
