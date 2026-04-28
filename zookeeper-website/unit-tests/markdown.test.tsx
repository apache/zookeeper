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

import React from "react";
import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "./utils";
import { getMDXComponents, MdLayout } from "@/components/mdx-components";

describe("MDX Components", () => {
  describe("getMDXComponents", () => {
    it("returns MDX components configuration", () => {
      const components = getMDXComponents();

      expect(components).toHaveProperty("h1");
      expect(components).toHaveProperty("h2");
      expect(components).toHaveProperty("h3");
      expect(components).toHaveProperty("p");
      expect(components).toHaveProperty("a");
      expect(components).toHaveProperty("ul");
      expect(components).toHaveProperty("ol");
      expect(components).toHaveProperty("li");
      expect(components).toHaveProperty("blockquote");
      expect(components).toHaveProperty("table");
    });

    it("accepts overrides", () => {
      const customH1 = () => <h1>Custom</h1>;
      const components = getMDXComponents({ h1: customH1 });

      expect(components.h1).toBe(customH1);
    });
  });

  describe("Heading Components", () => {
    it("renders h1 with correct styling", () => {
      const components = getMDXComponents();
      const H1 = components.h1 as React.ComponentType<any>;

      renderWithProviders(<H1>Heading 1</H1>);

      const heading = screen.getByRole("heading", { level: 1 });
      expect(heading).toHaveTextContent("Heading 1");
      expect(heading).toBeInTheDocument();
    });

    it("renders h2 with correct styling", () => {
      const components = getMDXComponents();
      const H2 = components.h2 as React.ComponentType<any>;

      renderWithProviders(<H2>Heading 2</H2>);

      const heading = screen.getByRole("heading", { level: 2 });
      expect(heading).toHaveTextContent("Heading 2");
      expect(heading).toBeInTheDocument();
    });

    it("renders h3 with correct styling", () => {
      const components = getMDXComponents();
      const H3 = components.h3 as React.ComponentType<any>;

      renderWithProviders(<H3>Heading 3</H3>);

      const heading = screen.getByRole("heading", { level: 3 });
      expect(heading).toHaveTextContent("Heading 3");
      expect(heading).toBeInTheDocument();
    });
  });

  describe("Link Component", () => {
    it("renders internal links without target blank", () => {
      const components = getMDXComponents();
      const A = components.a as React.ComponentType<any>;

      renderWithProviders(<A href="/docs/test">Internal Link</A>);

      const link = screen.getByRole("link", { name: "Internal Link" });
      expect(link).toHaveAttribute("href", "/docs/test");
      expect(link).not.toHaveAttribute("target", "_blank");
    });

    it("renders external links with target blank and icon", () => {
      const components = getMDXComponents();
      const A = components.a as React.ComponentType<any>;

      renderWithProviders(<A href="https://example.com">External Link</A>);

      const link = screen.getByRole("link", { name: /External Link/i });
      expect(link).toHaveAttribute("href", "https://example.com");
      expect(link).toHaveAttribute("target", "_blank");
      expect(link).toHaveAttribute("rel", "noopener noreferrer");
    });

    it("treats zookeeper.apache.org links as internal", () => {
      const components = getMDXComponents();
      const A = components.a as React.ComponentType<any>;

      renderWithProviders(
        <A href="https://zookeeper.apache.org/docs">Apache Link</A>
      );

      const link = screen.getByRole("link", { name: "Apache Link" });
      expect(link).not.toHaveAttribute("target", "_blank");
    });
  });

  describe("Image Component", () => {
    it("renders images with lazy loading", () => {
      const components = getMDXComponents();
      const Img = components.img as React.ComponentType<any>;

      renderWithProviders(<Img src="/test.png" alt="Test image" />);

      const img = screen.getByAltText("Test image");
      expect(img).toHaveAttribute("src", "/test.png");
      expect(img).toHaveAttribute("loading", "lazy");
    });
  });

  describe("MdLayout", () => {
    it("renders MDX content with layout wrapper", () => {
      const MockContent = ({ components }: any) => {
        const H1 = components.h1 as React.ComponentType<any>;
        const P = components.p as React.ComponentType<any>;
        return (
          <>
            <H1>Test Title</H1>
            <P>Test content</P>
          </>
        );
      };

      renderWithProviders(<MdLayout Content={MockContent} />);

      expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
        "Test Title"
      );
      expect(screen.getByText("Test content")).toBeInTheDocument();
    });

    it("applies custom className", () => {
      const MockContent = () => <div>Content</div>;

      const { container } = renderWithProviders(
        <MdLayout Content={MockContent} className="custom-class" />
      );

      expect(container.querySelector(".custom-class")).toBeInTheDocument();
    });

    it("passes overrides to getMDXComponents", () => {
      const CustomH1 = ({ children }: any) => (
        <h1 data-testid="custom-h1">{children}</h1>
      );
      const MockContent = ({ components }: any) => {
        const H1 = components.h1 as React.ComponentType<any>;
        return <H1>Custom Heading</H1>;
      };

      renderWithProviders(
        <MdLayout Content={MockContent} overrides={{ h1: CustomH1 }} />
      );

      expect(screen.getByTestId("custom-h1")).toHaveTextContent(
        "Custom Heading"
      );
    });
  });
});
