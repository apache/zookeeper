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

import { describe, it, expect, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "./utils";
import { ThemeToggle } from "@/components/theme-toggle";

describe("ThemeToggle", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("renders the theme toggle button", () => {
    renderWithProviders(<ThemeToggle />);

    const button = screen.getByRole("button", { name: /Toggle theme/i });
    expect(button).toBeInTheDocument();
  });

  it("toggles theme when clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ThemeToggle />);

    const button = screen.getByRole("button", { name: /Toggle theme/i });

    // Initially should be light theme (default)
    expect(document.documentElement.classList.contains("dark")).toBe(false);

    // Click to toggle to dark
    await user.click(button);
    expect(document.documentElement.classList.contains("dark")).toBe(true);

    // Click to toggle back to light
    await user.click(button);
    expect(document.documentElement.classList.contains("dark")).toBe(false);
  });

  it("has sun and moon icons", () => {
    renderWithProviders(<ThemeToggle />);

    // Both icons should be in the DOM (one visible, one hidden based on theme)
    const button = screen.getByRole("button", { name: /Toggle theme/i });
    expect(button).toBeInTheDocument();

    // Check SVG elements exist (lucide-react renders as svg)
    const svgs = button.querySelectorAll("svg");
    expect(svgs.length).toBe(2); // Sun and Moon icons
  });
});
