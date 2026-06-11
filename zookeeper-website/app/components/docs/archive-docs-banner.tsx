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

import { ArrowRight, X } from "lucide-react";
import { useEffect, useState } from "react";
import { CURRENT_VERSION } from "@/lib/current-version";
import { DOCS_ARCHIVE_SNAPSHOT_ENV } from "@/lib/docs-archive";
import { Button } from "@/ui/button";

const bannerStorageKey = `zookeeper-docs-archive-banner-dismissed:${CURRENT_VERSION}`;

export function ArchiveDocsBanner() {
  // Set only by `build:docs-archive`, never by the current docs build or dev.
  const isArchiveDocsView = import.meta.env[DOCS_ARCHIVE_SNAPSHOT_ENV] === "1";
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    if (!isArchiveDocsView) {
      return;
    }

    setVisible(localStorage.getItem(bannerStorageKey) !== "1");
  }, [isArchiveDocsView]);

  if (!isArchiveDocsView || !visible) {
    return null;
  }

  const dismiss = () => {
    localStorage.setItem(bannerStorageKey, "1");
    setVisible(false);
  };

  return (
    <div
      role="status"
      className="bg-muted text-foreground border-b px-4 py-2 text-sm"
    >
      <div className="flex items-center gap-4">
        <p className="mx-auto text-center">
          You are viewing the v{CURRENT_VERSION} docs.{" "}
          <a
            href="/doc/"
            className="inline-flex items-center gap-1 font-medium underline underline-offset-4"
          >
            Switch to latest
            <ArrowRight className="h-4 w-4" aria-hidden />
          </a>
        </p>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="size-8 shrink-0"
          aria-label="Dismiss archived docs notice"
          onClick={dismiss}
        >
          <X className="h-4 w-4" />
        </Button>
      </div>
    </div>
  );
}
