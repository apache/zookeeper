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
  create,
  insertMultiple,
  type Orama,
  type PartialSchemaDeep,
  type TypedDocument
} from "@orama/orama";
import type { AdvancedOptions } from "fumadocs-core/search/server";

export type AdvancedDocument = TypedDocument<Orama<typeof advancedSchema>>;
export const advancedSchema = {
  content: "string",
  page_id: "string",
  type: "string",
  breadcrumbs: "string[]",
  tags: "enum[]",
  url: "string",
  embeddings: "vector[512]"
} as const;

export async function createDB({
  indexes,
  tokenizer,
  ...rest
}: AdvancedOptions): Promise<Orama<typeof advancedSchema>> {
  const items = typeof indexes === "function" ? await indexes() : indexes;

  const db = create({
    schema: advancedSchema,
    ...rest,
    components: {
      ...rest.components,
      tokenizer: tokenizer ?? rest.components?.tokenizer
    }
  }) as Orama<typeof advancedSchema>;

  const mapTo: PartialSchemaDeep<AdvancedDocument>[] = [];
  items.forEach((page) => {
    const pageTag = page.tag ?? [];
    const tags = Array.isArray(pageTag) ? pageTag : [pageTag];
    const data = page.structuredData;
    let id = 0;

    mapTo.push({
      id: page.id,
      page_id: page.id,
      type: "page",
      content: page.title,
      breadcrumbs: page.breadcrumbs,
      tags,
      url: page.url
    });

    const nextId = () => `${page.id}-${id++}`;

    if (page.description) {
      mapTo.push({
        id: nextId(),
        page_id: page.id,
        tags,
        type: "text",
        url: page.url,
        content: page.description
      });
    }

    for (const heading of data.headings) {
      mapTo.push({
        id: nextId(),
        page_id: page.id,
        type: "heading",
        tags,
        url: `${page.url}#${heading.id}`,
        content: heading.content
      });
    }

    for (const content of data.contents) {
      mapTo.push({
        id: nextId(),
        page_id: page.id,
        tags,
        type: "text",
        url: content.heading ? `${page.url}#${content.heading}` : page.url,
        content: content.content
      });
    }
  });

  await insertMultiple(db, mapTo);
  return db;
}
