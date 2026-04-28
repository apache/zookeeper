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

import type { Route } from "./+types/404";
import { Link } from "@/components/link";
import { Button } from "@/ui/button";

export function meta({}: Route.MetaArgs) {
  return [
    { title: "Page Not Found - Apache ZooKeeper" },
    {
      name: "description",
      content:
        "The page you are looking for doesn't exist. The website was updated recently, so the route you were trying to visit might have changed."
    }
  ];
}

export default function NotFoundPage() {
  return (
    <section className="container mx-auto px-4 py-16 md:py-24">
      <div className="mx-auto flex max-w-2xl flex-col items-center text-center">
        <p className="text-muted-foreground text-sm font-semibold tracking-[0.3em] uppercase">
          404
        </p>
        <h1 className="mt-4 text-4xl font-semibold tracking-tight text-balance md:text-6xl">
          Page not found
        </h1>
        <p className="text-muted-foreground mt-4 text-lg text-pretty md:text-xl">
          The page you are looking for doesn&apos;t exist. The website was
          updated recently, so the route you were trying to visit might have
          changed.
        </p>
        <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
          <Button asChild size="lg">
            <Link to="/">Go back home</Link>
          </Button>
        </div>
      </div>
    </section>
  );
}
