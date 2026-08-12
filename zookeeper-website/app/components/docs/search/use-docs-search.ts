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

import { type DependencyList, useRef, useState } from "react";
import { type StaticOptions } from "fumadocs-core/search/client";
import type { SortedResult } from "fumadocs-core/search";
import { useDebounce, useOnChange } from "./utils";

interface UseDocsSearch {
  search: string;
  setSearch: (v: string) => void;
  query: {
    isLoading: boolean;
    data?: SortedResult[] | "empty";
    error?: Error;
  };
}

export type Client = {
  type: "static";
} & StaticOptions;

function isDeepEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;

  if (Array.isArray(a) && Array.isArray(b)) {
    return b.length === a.length && a.every((v, i) => isDeepEqual(v, b[i]));
  }

  if (typeof a === "object" && a && typeof b === "object" && b) {
    const aKeys = Object.keys(a);
    const bKeys = Object.keys(b);

    return (
      aKeys.length === bKeys.length &&
      aKeys.every(
        (key) =>
          Object.hasOwn(b, key) &&
          isDeepEqual(a[key as keyof object], b[key as keyof object])
      )
    );
  }

  return false;
}

/**
 * Provide a hook to query different official search clients.
 *
 * Note: it will re-query when its parameters changed, make sure to use `useMemo()` on `clientOptions` or define `deps` array.
 */
export function useDocsSearch(
  clientOptions: Client & {
    /**
     * The debounced delay for performing a search (in ms).
     * .
     * @defaultValue 100
     */
    delayMs?: number;

    /**
     * still perform search even if query is empty.
     *
     * @defaultValue false
     */
    allowEmpty?: boolean;
  },
  deps?: DependencyList
): UseDocsSearch {
  const { delayMs = 100, allowEmpty = false, ...client } = clientOptions;

  const [search, setSearch] = useState("");
  const [results, setResults] = useState<SortedResult[] | "empty">("empty");
  const [error, setError] = useState<Error>();
  const [isLoading, setIsLoading] = useState(false);
  const debouncedValue = useDebounce(search, delayMs);
  const onStart = useRef<() => void>(undefined);

  useOnChange(
    [deps ?? clientOptions, debouncedValue],
    () => {
      if (onStart.current) {
        onStart.current();
        onStart.current = undefined;
      }

      setIsLoading(true);
      let interrupt = false;
      onStart.current = () => {
        interrupt = true;
      };

      async function run(): Promise<SortedResult[] | "empty"> {
        if (debouncedValue.length === 0 && !allowEmpty) return "empty";
        switch (client.type) {
          case "static": {
            const { search } = await import("./static");
            return search(debouncedValue, client);
          }
          default:
            throw new Error("unknown search client");
        }
      }

      void run()
        .then((res) => {
          if (interrupt) return;

          setError(undefined);
          setResults(res);
        })
        .catch((err: Error) => {
          setError(err);
        })
        .finally(() => {
          setIsLoading(false);
        });
    },
    deps ? undefined : (a, b) => !isDeepEqual(a, b)
  );

  return { search, setSearch, query: { isLoading, data: results, error } };
}
