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

import type { ComponentProps } from "react";
import { useI18n } from "fumadocs-ui/contexts/i18n";
import { Popover, PopoverContent, PopoverTrigger } from "../../../ui/popover";
import { buttonVariants } from "../../../ui/button";
import { cn } from "@/lib/utils";

export type LanguageSelectProps = ComponentProps<"button">;

export function LanguageToggle(props: LanguageSelectProps): React.ReactElement {
  const context = useI18n();
  if (!context.locales) throw new Error("Missing `<I18nProvider />`");

  return (
    <Popover>
      <PopoverTrigger
        aria-label={context.text.chooseLanguage}
        {...props}
        className={cn(
          buttonVariants({
            variant: "ghost",
            className: "gap-1.5 p-1.5"
          }),
          props.className
        )}
      >
        {props.children}
      </PopoverTrigger>
      <PopoverContent className="flex flex-col overflow-x-hidden p-0">
        <p className="text-fd-muted-foreground mb-1 p-2 text-xs font-medium">
          {context.text.chooseLanguage}
        </p>
        {context.locales.map((item) => (
          <button
            key={item.locale}
            type="button"
            className={cn(
              "p-2 text-start text-sm",
              item.locale === context.locale
                ? "bg-fd-primary/10 text-fd-primary font-medium"
                : "hover:bg-fd-accent hover:text-fd-accent-foreground"
            )}
            onClick={() => {
              context.onChange?.(item.locale);
            }}
          >
            {item.name}
          </button>
        ))}
      </PopoverContent>
    </Popover>
  );
}

export function LanguageToggleText(props: ComponentProps<"span">) {
  const context = useI18n();
  const text = context.locales?.find(
    (item) => item.locale === context.locale
  )?.name;

  return <span {...props}>{text}</span>;
}
