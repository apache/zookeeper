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

import { docs } from "@/.source";
import { toClientRenderer } from "fumadocs-mdx/runtime/vite";
import { DocsLayout } from "@/components/docs/layout/docs";
import {
  DocsBody as FumaDocsBody,
  DocsDescription as FumaDocsDescription,
  DocsPage as FumaDocsPage,
  DocsTitle as FumaDocsTitle
} from "@/components/docs/layout/docs/page";
import defaultMdxComponents from "fumadocs-ui/mdx";
import type * as PageTree from "fumadocs-core/page-tree";
import type { BaseLayoutProps } from "fumadocs-ui/layouts/shared";
import { useParams } from "react-router";
import { getPageTreePeers } from "fumadocs-core/page-tree";
import { Card, Cards } from "fumadocs-ui/components/card";
import { Callout } from "fumadocs-ui/components/callout";
import { Step, Steps } from "fumadocs-ui/components/steps";
import { Link } from "@/components/link";
import { OlderDocsPicker } from "@/components/docs/older-docs-picker";
import type { MDXComponents } from "mdx/types";

// Extend default MDX components to include shared UI blocks globally.
// Note: We'll override the 'a' component in the renderer to handle route-specific logic
const baseMdxComponents: MDXComponents = {
  ...defaultMdxComponents,
  p: (props) => <p className="wrap-anywhere" {...props} />,
  h1: (props) => <h1 className="font-bold" {...props} />,
  Callout,
  Step,
  Steps
};

export function baseOptions(): BaseLayoutProps {
  return {
    nav: {
      title: (
        <div className="flex items-center gap-2">
          <img
            src="/favicon.ico"
            alt="ZooKeeper favicon"
            width={16}
            height={16}
          />
          <p>Apache ZooKeeper</p>
        </div>
      )
    },
    githubUrl: "https://github.com/apache/zookeeper/"
  };
}

type DocsLoaderData = { path: string; url: string; tree: unknown };

const renderer = toClientRenderer(
  docs.doc,
  (
    {
      toc,
      default: Mdx,
      frontmatter,
      pageTitle,
      pageDescription
    }: {
      toc: any;
      default: any;
      frontmatter: { title?: string; description?: string };
      pageTitle?: string;
      pageDescription?: string;
    },
    { tree }: { tree: PageTree.Root }
  ) => {
    const route = useParams()["*"];
    const baseGithubPath = "zookeeper-docs/app/pages/_docs/docs/_mdx/";

    const groupedRoutes = [
      "developer/programmers-guide",
      "admin-ops/administrators-guide"
    ];
    const trimmedRoute = route?.endsWith("/") ? route?.slice(0, -1) : route;
    const mdxFileRoute = `${trimmedRoute === "" ? "index" : trimmedRoute}.mdx`;
    const isGroupedRoute =
      !!trimmedRoute && groupedRoutes.includes(trimmedRoute);
    const displayTitle = pageTitle ?? frontmatter.title;
    const displayDescription = pageDescription ?? frontmatter.description;

    // Use default Link component for all links (external links are handled by Link component)
    const CustomLink = ({ href, children, ...rest }: any) => {
      return (
        <Link to={href} {...rest}>
          {children}
        </Link>
      );
    };

    const mdxComponents = {
      ...baseMdxComponents,
      a: CustomLink
    };

    return (
      <FumaDocsPage toc={toc} tableOfContent={{ style: "clerk" }}>
        <title>{displayTitle + " | Zookeeper"}</title>
        <meta name="description" content={displayDescription} />

        <FumaDocsTitle>{displayTitle}</FumaDocsTitle>
        <FumaDocsDescription>{displayDescription}</FumaDocsDescription>
        <FumaDocsBody>
          <Mdx components={mdxComponents} />
          {route !== undefined && isGroupedRoute && (
            // table of content for grouped routes
            <div className="flex flex-col">
              <p>In this section:</p>
              <Cards>
                {getPageTreePeers(tree, `/docs/${trimmedRoute}`).map((peer) => (
                  <Card key={peer.url} title={peer.name} href={peer.url}>
                    {peer.description}
                  </Card>
                ))}
              </Cards>
            </div>
          )}
        </FumaDocsBody>

        {route !== undefined && (
          <a
            href={`https://github.com/apache/zookeeper/${baseGithubPath}${mdxFileRoute}`}
            rel="noreferrer noopener"
            target="_blank"
            className="text-fd-secondary-foreground bg-fd-secondary hover:text-fd-accent-foreground hover:bg-fd-accent w-fit rounded-xl border p-2 text-sm font-medium transition-colors"
          >
            Edit on GitHub
          </a>
        )}
      </FumaDocsPage>
    );
  }
);

export function DocsPage({ loaderData }: { loaderData: DocsLoaderData }) {
  const { tree, path } = loaderData;
  const Content = renderer[path];

  const layoutOptions = baseOptions();

  return (
    <DocsLayout
      {...layoutOptions}
      tree={tree as PageTree.Root}
      sidebar={{ banner: <OlderDocsPicker /> }}
    >
      <Content tree={tree as PageTree.Root} />
    </DocsLayout>
  );
}
