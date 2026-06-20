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

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ArchiveDocsBanner } from "@/components/docs/archive-docs-banner";
import { CURRENT_VERSION } from "@/lib/current-version";
import { DOCS_ARCHIVE_SNAPSHOT_ENV } from "@/lib/docs-archive";

const storageKey = `zookeeper-docs-archive-banner-dismissed:${CURRENT_VERSION}`;

function enableArchiveBuild() {
  vi.stubEnv(DOCS_ARCHIVE_SNAPSHOT_ENV, "1");
}

beforeEach(() => {
  localStorage.clear();
});

afterEach(() => {
  vi.unstubAllEnvs();
  localStorage.clear();
});

describe("ArchiveDocsBanner", () => {
  it("renders nothing outside an archive build", () => {
    render(<ArchiveDocsBanner />);

    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("shows the switch-to-latest notice on an archive snapshot", () => {
    enableArchiveBuild();
    render(<ArchiveDocsBanner />);

    expect(
      screen.getByText(new RegExp(`viewing the v${CURRENT_VERSION} docs`, "i"))
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /switch to latest/i })
    ).toHaveAttribute("href", "/doc/");
  });

  it("dismisses the banner and remembers the dismissal", async () => {
    const user = userEvent.setup();
    enableArchiveBuild();
    render(<ArchiveDocsBanner />);

    expect(screen.getByRole("status")).toBeInTheDocument();

    await user.click(
      screen.getByRole("button", { name: /dismiss archived docs notice/i })
    );

    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(localStorage.getItem(storageKey)).toBe("1");
  });

  it("stays hidden when already dismissed", () => {
    enableArchiveBuild();
    localStorage.setItem(storageKey, "1");
    render(<ArchiveDocsBanner />);

    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });
});
