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

import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "./utils";
import { HomePage } from "@/pages/_landing/home";

describe("HomePage", () => {
  it("renders the hero section with main heading", () => {
    renderWithProviders(<HomePage />);

    const heading = screen.getByRole("heading", {
      name: /The standard for distributed coordination/i
    });
    expect(heading).toBeInTheDocument();
  });

  it("displays the Apache ZooKeeper logo", () => {
    renderWithProviders(<HomePage />);

    const logos = screen.getAllByAltText(/Apache ZooKeeper logo/i);
    expect(logos.length).toBeGreaterThan(0);
  });

  it("shows the main description text", () => {
    renderWithProviders(<HomePage />);

    const description = screen.getByText(
      /Naming, configuration management, synchronization/i
    );
    expect(description).toBeInTheDocument();
  });

  it("displays Download button", () => {
    renderWithProviders(<HomePage />);

    const downloadButton = screen.getByRole("link", {
      name: /^Download$/i
    });
    expect(downloadButton).toBeInTheDocument();
    expect(downloadButton).toHaveAttribute("href", "/releases");
  });

  it("displays Read Documentation button", () => {
    renderWithProviders(<HomePage />);

    const docsButton = screen.getByRole("link", {
      name: /Read Documentation/i
    });
    expect(docsButton).toBeInTheDocument();
  });

  it("renders the features section", () => {
    renderWithProviders(<HomePage />);

    // Check for key feature headings
    expect(screen.getByText("High Performance")).toBeInTheDocument();
    expect(screen.getByText("Simple & Reliable")).toBeInTheDocument();
  });

  it("renders use cases section", () => {
    renderWithProviders(<HomePage />);

    // Check for use cases section by ID
    const useCases = document.querySelector("#use-cases");
    expect(useCases).toBeInTheDocument();
  });

  it("renders community section", () => {
    renderWithProviders(<HomePage />);

    // Look for a heading specific to the community section
    expect(
      screen.getByRole("heading", { name: /Vibrant Community/i })
    ).toBeInTheDocument();
  });
});
