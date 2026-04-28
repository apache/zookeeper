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

import { ChevronRight, BookOpen } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList
} from "@/ui/command";
import { getReleasedDocVersions } from "@/lib/released-docs-versions";

interface OlderDocsVersionsProps {
  versions?: string[];
}

/** Shared Command palette (search + list), used in both the
 *  sidebar popover and the navbar sub-menu. */
export function OlderDocsVersionList({
  versions = getReleasedDocVersions()
}: OlderDocsVersionsProps) {
  return (
    <Command filter={(value, search) => (value.includes(search) ? 1 : 0)}>
      <CommandInput placeholder="Search version..." />
      <CommandList>
        <CommandEmpty>No versions found</CommandEmpty>
        <CommandGroup>
          {versions.map((version) => (
            <CommandItem key={version} value={version} asChild>
              <a href={`/released-docs/r${version}/index.html`}>{version}</a>
            </CommandItem>
          ))}
        </CommandGroup>
      </CommandList>
    </Command>
  );
}

/** Sidebar variant — Popover that opens to the right of the trigger button. */
export function OlderDocsPicker({ versions }: OlderDocsVersionsProps) {
  return (
    <Popover>
      <PopoverTrigger asChild>
        <button className="text-fd-muted-foreground hover:bg-fd-accent/50 hover:text-fd-accent-foreground/80 relative flex w-full flex-row items-center gap-2 rounded-lg p-2 text-start transition-colors hover:transition-none [&_svg]:size-4 [&_svg]:shrink-0">
          <BookOpen />
          <span className="flex-1 text-sm">Older docs</span>
          <ChevronRight className="ms-auto opacity-60" />
        </button>
      </PopoverTrigger>
      <PopoverContent
        side="bottom"
        align="center"
        sideOffset={8}
        className="w-56 p-0"
        aria-label="Older documentation versions"
      >
        <OlderDocsVersionList versions={versions} />
      </PopoverContent>
    </Popover>
  );
}
