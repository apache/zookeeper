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

import { useEffect } from "react";
import { useParams } from "react-router";

function getLivePath(splat?: string) {
  return splat ? `/${splat}` : "/";
}

export function meta() {
  return [
    { title: "Redirecting - Apache ZooKeeper" },
    { "http-equiv": "refresh", content: "0; url=/" }
  ];
}

export default function RedirectToLiveRoute() {
  const params = useParams();
  const livePath = getLivePath(params["*"]);

  useEffect(() => {
    window.location.replace(livePath);
  }, [livePath]);

  return (
    <main className="bg-background text-foreground flex min-h-screen items-center justify-center px-6">
      <section className="border-border bg-card max-w-lg rounded-2xl border p-8 text-center shadow-sm">
        <img
          src={`${import.meta.env.BASE_URL}images/logo.svg`}
          alt="Apache ZooKeeper"
          className="mx-auto mb-6 h-16 w-16"
        />
        <h1 className="mb-3 text-2xl font-semibold">
          This archived page has moved
        </h1>
        <p className="text-muted-foreground mb-6">
          This archive keeps versioned documentation. You are being sent to the
          current Apache ZooKeeper page for this route.
        </p>
        <a
          href={livePath}
          className="bg-primary text-primary-foreground hover:bg-primary/90 inline-flex h-10 items-center justify-center rounded-md px-5 text-sm font-medium transition-colors"
        >
          Continue to current site
        </a>
      </section>
    </main>
  );
}
