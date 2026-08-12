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

import { useEffect, useState } from "react";

export function removeUndefined<T extends object>(value: T, deep = false): T {
  const obj = value as Record<string, unknown>;

  for (const key in obj) {
    if (obj[key] === undefined) delete obj[key];
    if (!deep) continue;

    const entry = obj[key];

    if (typeof entry === "object" && entry !== null) {
      removeUndefined(entry, deep);
      continue;
    }

    if (Array.isArray(entry)) {
      for (const item of entry) removeUndefined(item, deep);
    }
  }

  return value;
}

/**
 * @param value - state to watch
 * @param onChange - when the state changed
 * @param isUpdated - a function that determines if the state is updated
 */
export function useOnChange<T>(
  value: T,
  onChange: (current: T, previous: T) => void,
  isUpdated: (prev: T, current: T) => boolean = isDifferent
): void {
  const [prev, setPrev] = useState<T>(value);

  if (isUpdated(prev, value)) {
    onChange(value, prev);
    setPrev(value);
  }
}

function isDifferent(a: unknown, b: unknown): boolean {
  if (Array.isArray(a) && Array.isArray(b)) {
    return b.length !== a.length || a.some((v, i) => isDifferent(v, b[i]));
  }

  return a !== b;
}

export function useDebounce<T>(value: T, delayMs = 1000): T {
  const [debouncedValue, setDebouncedValue] = useState(value);

  useEffect(() => {
    if (delayMs === 0) return;
    const handler = window.setTimeout(() => {
      setDebouncedValue(value);
    }, delayMs);

    return () => clearTimeout(handler);
  }, [delayMs, value]);

  if (delayMs === 0) return value;
  return debouncedValue;
}
