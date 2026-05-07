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
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "./utils";
import { OlderDocsPicker } from "@/components/docs/older-docs-picker";
import {
  RELEASED_DOC_VERSIONS,
  sortVersionsDesc
} from "@/lib/released-docs-versions";

const MOCK_RELEASED_DOC_VERSIONS = ["3.10.0", "3.9.4", "3.9.0-beta"];

describe("sortVersionsDesc", () => {
  it("sorts stable versions newest first", () => {
    const result = sortVersionsDesc(["3.7.0", "3.9.0", "3.8.0"]);
    expect(result).toEqual(["3.9.0", "3.8.0", "3.7.0"]);
  });

  it("sorts patch versions correctly", () => {
    const result = sortVersionsDesc(["3.8.1", "3.8.10", "3.8.2"]);
    expect(result).toEqual(["3.8.10", "3.8.2", "3.8.1"]);
  });

  it("places stable releases above pre-releases of the same base", () => {
    const result = sortVersionsDesc([
      "3.5.0-alpha",
      "3.5.0-beta" as string,
      "3.5.0"
    ]);
    // stable > beta > alpha for the same version
    expect(result[0]).toBe("3.5.0");
    expect(result[2]).toBe("3.5.0-alpha");
  });

  it("sorts alpha before beta for same base version", () => {
    const result = sortVersionsDesc(["3.5.4-beta", "3.5.0-alpha"]);
    expect(result[0]).toBe("3.5.4-beta");
    expect(result[1]).toBe("3.5.0-alpha");
  });
});

describe("RELEASED_DOC_VERSIONS", () => {
  it("is an array of strings", () => {
    expect(Array.isArray(RELEASED_DOC_VERSIONS)).toBe(true);
    RELEASED_DOC_VERSIONS.forEach((version) => {
      expect(typeof version).toBe("string");
    });
  });

  it("is already sorted newest to oldest", () => {
    const sorted = sortVersionsDesc([...RELEASED_DOC_VERSIONS]);
    expect(RELEASED_DOC_VERSIONS).toEqual(sorted);
  });
});

describe("OlderDocsPicker", () => {
  it("renders the trigger button", () => {
    renderWithProviders(
      <OlderDocsPicker versions={MOCK_RELEASED_DOC_VERSIONS} />
    );
    expect(
      screen.getByRole("button", { name: /older docs/i })
    ).toBeInTheDocument();
  });

  it("does not show the version list before the button is clicked", () => {
    renderWithProviders(
      <OlderDocsPicker versions={MOCK_RELEASED_DOC_VERSIONS} />
    );
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  it("opens the popover and shows a search combobox on trigger click", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <OlderDocsPicker versions={MOCK_RELEASED_DOC_VERSIONS} />
    );

    await user.click(screen.getByRole("button", { name: /older docs/i }));

    await waitFor(() => {
      expect(screen.getByRole("combobox")).toBeInTheDocument();
    });
  });

  it("shows all versions when popover is open", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <OlderDocsPicker versions={MOCK_RELEASED_DOC_VERSIONS} />
    );

    await user.click(screen.getByRole("button", { name: /older docs/i }));

    await waitFor(() => {
      expect(screen.getByRole("listbox")).toBeInTheDocument();
    });

    const items = screen.getAllByRole("option");
    expect(items.length).toBe(MOCK_RELEASED_DOC_VERSIONS.length);
  });

  it("filters versions by search query", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <OlderDocsPicker versions={MOCK_RELEASED_DOC_VERSIONS} />
    );

    await user.click(screen.getByRole("button", { name: /older docs/i }));

    const input = await screen.findByRole("combobox");
    await user.type(input, "3.9");

    await waitFor(() => {
      const items = screen.getAllByRole("option");
      // Every visible option must match the search term
      items.forEach((item) => {
        expect(item.textContent?.trim()).toContain("3.9");
      });
    });
  });

  it("shows 'No versions found' when search has no matches", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <OlderDocsPicker versions={MOCK_RELEASED_DOC_VERSIONS} />
    );

    await user.click(screen.getByRole("button", { name: /older docs/i }));

    const input = await screen.findByRole("combobox");
    await user.type(input, "99.99.99");

    await waitFor(() => {
      expect(screen.getByText(/no versions found/i)).toBeInTheDocument();
    });
  });

  it("each version links to the correct /released-docs/ path", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <OlderDocsPicker versions={MOCK_RELEASED_DOC_VERSIONS} />
    );

    await user.click(screen.getByRole("button", { name: /older docs/i }));

    await waitFor(() => {
      const items = screen.getAllByRole("option");
      expect(items.length).toBeGreaterThan(0);
      items.forEach((item) => {
        // With asChild, the <a> IS the option element
        expect(item.getAttribute("href")).toMatch(
          /^\/released-docs\/r[\d.].+\/index\.html$/
        );
      });
    });
  });

  it("shows an empty state when no older docs are available", async () => {
    const user = userEvent.setup();
    renderWithProviders(<OlderDocsPicker versions={[]} />);

    await user.click(screen.getByRole("button", { name: /older docs/i }));

    await waitFor(() => {
      expect(screen.getByText(/no versions found/i)).toBeInTheDocument();
    });
  });

  it("clears search when popover is reopened", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <OlderDocsPicker versions={MOCK_RELEASED_DOC_VERSIONS} />
    );

    const trigger = screen.getByRole("button", { name: /older docs/i });

    await user.click(trigger);
    const input = await screen.findByRole("combobox");
    await user.type(input, "3.9");

    // Close and reopen — Radix unmounts the popover content, resetting Command state
    await user.keyboard("{Escape}");
    await user.click(trigger);

    const newInput = await screen.findByRole("combobox");
    expect(newInput).toHaveValue("");
  });
});
