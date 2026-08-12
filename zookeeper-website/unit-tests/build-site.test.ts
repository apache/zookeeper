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

import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { access, mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { pruneLandingRootDocsImages } from "../scripts/build-site";

let tempDir: string;

async function pathExists(path: string): Promise<boolean> {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
}

beforeEach(async () => {
  tempDir = await mkdtemp(join(tmpdir(), "zk-build-site-test-"));
});

afterEach(async () => {
  await rm(tempDir, { recursive: true, force: true });
});

describe("pruneLandingRootDocsImages", () => {
  it("removes docs images from the landing root without touching merged docs", async () => {
    await mkdir(join(tempDir, "docs-images"), { recursive: true });
    await writeFile(join(tempDir, "docs-images", "zkservice.jpg"), "");
    await mkdir(join(tempDir, "doc", "r3.10.0", "docs-images"), {
      recursive: true
    });
    await writeFile(
      join(tempDir, "doc", "r3.10.0", "docs-images", "zkservice.jpg"),
      ""
    );

    await pruneLandingRootDocsImages(tempDir);

    await expect(pathExists(join(tempDir, "docs-images"))).resolves.toBe(false);
    await expect(
      pathExists(join(tempDir, "doc", "r3.10.0", "docs-images"))
    ).resolves.toBe(true);
  });
});
