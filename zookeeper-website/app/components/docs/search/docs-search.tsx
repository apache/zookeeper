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

import {
  SearchDialog as FumaDocsSearchDialog,
  SearchDialogClose,
  SearchDialogContent,
  SearchDialogHeader,
  SearchDialogIcon,
  SearchDialogInput,
  SearchDialogList,
  SearchDialogOverlay,
  type SharedProps
} from "fumadocs-ui/components/dialog/search";
import { useDocsSearch } from "./use-docs-search";
import { create } from "@orama/orama";
import { useI18n } from "fumadocs-ui/contexts/i18n";

function initOrama() {
  return create({
    schema: { _: "string" },
    language: "english"
  });
}

export function SearchDialog(props: SharedProps) {
  const { locale } = useI18n();

  const { search, setSearch, query } = useDocsSearch({
    type: "static",
    initOrama,
    locale,
    tag: "multi-page"
  });

  return (
    <FumaDocsSearchDialog
      search={search}
      onSearchChange={setSearch}
      isLoading={query.isLoading}
      {...props}
    >
      <SearchDialogOverlay />
      <SearchDialogContent>
        <SearchDialogHeader>
          <SearchDialogIcon />
          <SearchDialogInput />
          <SearchDialogClose />
        </SearchDialogHeader>
        <SearchDialogList
          items={
            query.data !== "empty"
              ? query.data?.map((i) => ({
                  ...i,
                  breadcrumbs: i.breadcrumbs?.filter(
                    (k) => k !== "Multi-Page Documentation"
                  )
                }))
              : null
          }
        />
      </SearchDialogContent>
    </FumaDocsSearchDialog>
  );
}
