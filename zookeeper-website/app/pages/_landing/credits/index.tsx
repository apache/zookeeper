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

import developers from "./developers.json";

interface Developer {
  id: string;
  name: string;
  email: string;
  timezone: string;
}

function DeveloperTable({ developers }: { developers: Developer[] }) {
  return (
    <div className="border-border my-8 w-full overflow-x-auto rounded-lg border">
      <table className="mt-0 mb-0 w-full table-fixed border-collapse text-sm">
        <thead className="bg-muted">
          <tr className="border-border border-b">
            <th className="px-4 py-3 text-left font-semibold">Username</th>
            <th className="px-4 py-3 text-left font-semibold">Name</th>
            <th className="px-4 py-3 text-left font-semibold">Email</th>
            <th className="px-4 py-3 text-left font-semibold">Time Zone</th>
          </tr>
        </thead>
        <tbody>
          {developers.map((developer) => (
            <tr
              key={developer.id}
              className="border-border hover:bg-muted/50 border-b transition-colors"
            >
              <td className="px-4 py-3 align-top">{developer.id}</td>
              <td className="px-4 py-3 align-top">{developer.name}</td>
              <td className="px-4 py-3 align-top">{developer.email}</td>
              <td className="px-4 py-3 align-top">{developer.timezone}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function CreditsPage() {
  return (
    <div className="container mx-auto px-4 py-12">
      <article className="prose prose-slate dark:prose-invert max-w-none">
        <h1 className="!my-12 text-center text-4xl font-semibold tracking-tight text-balance md:text-6xl">
          ZooKeeper Credits
        </h1>

        <p className="mb-4 text-base leading-7">
          A successful project requires many people to play many roles. Some
          members write code or documentation, while others are valuable as
          testers, submitting patches and suggestions.
        </p>

        <h2 className="mt-12 mb-4 scroll-mt-28 text-3xl font-semibold tracking-tight md:text-4xl">
          Developers
        </h2>

        <p className="mb-4 text-base leading-7">
          ZooKeeper&apos;s developers, as listed in the project&apos;s parent{" "}
          <code>pom.xml</code>, are shown below.
        </p>

        <DeveloperTable developers={developers} />

        <h2 className="mt-12 mb-4 scroll-mt-28 text-3xl font-semibold tracking-tight md:text-4xl">
          Contributors
        </h2>

        <p className="mb-4 text-base leading-7">
          A list of ZooKeeper contributors and their contributions is available
          from{" "}
          <a
            href="https://issues.apache.org/jira/browse/ZOOKEEPER"
            target="_blank"
            rel="noopener noreferrer"
            className="text-primary underline-offset-4 hover:underline"
          >
            Jira
          </a>
          .
        </p>
      </article>
    </div>
  );
}
