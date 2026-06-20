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

import { Button } from "@/ui/button";
import { ArrowUpRight } from "lucide-react";
import { Link } from "@/components/link";

export function CommunitySection() {
  return (
    <section id="community" className="relative overflow-hidden">
      <div className="from-primary/10 via-background to-background absolute inset-0 -z-10 bg-[radial-gradient(ellipse_at_bottom_right,_var(--tw-gradient-stops))]"></div>
      <div className="container mx-auto px-4 py-16 md:py-24">
        <div className="grid grid-cols-1 items-center gap-16 lg:grid-cols-2">
          <div>
            <h2 className="text-3xl font-bold tracking-tight md:text-4xl">
              A Vibrant Community
            </h2>
            <p className="text-muted-foreground mt-4 text-lg leading-relaxed">
              ZooKeeper is a top-level Apache project with an active community
              of users and contributors. Join discussions, read the
              documentation, and help shape the roadmap.
            </p>
            <div className="mt-8 flex flex-wrap gap-4">
              <Button asChild size="lg">
                <Link to="/mailing-lists">Mailing Lists</Link>
              </Button>
              <Button asChild variant="outline" size="lg">
                <Link to="https://github.com/apache/zookeeper" target="_blank">
                  Contribute
                </Link>
              </Button>
              <Button asChild variant="ghost" size="lg">
                <Link to="/credits">Credits</Link>
              </Button>
            </div>
          </div>
          <ul className="grid gap-4 text-sm leading-6">
            <li id="news" className="relative p-0">
              <Link
                to="/news"
                className="group border-border/60 bg-card/50 hover:bg-card hover:border-primary/50 focus-visible:ring-primary block rounded-2xl border p-6 shadow-sm transition-all duration-200 hover:shadow-md focus-visible:ring-2 focus-visible:outline-none"
              >
                <span className="text-foreground text-lg font-semibold">
                  News
                </span>
                <p className="text-muted-foreground mt-1 text-base">
                  ZooKeeper release announcements and project news.
                </p>
                <ArrowUpRight className="text-muted-foreground group-hover:text-primary absolute top-6 right-6 size-5 transition-colors" />
              </Link>
            </li>
            <li id="irc" className="relative p-0">
              <Link
                to="/irc"
                className="group border-border/60 bg-card/50 hover:bg-card hover:border-primary/50 focus-visible:ring-primary block rounded-2xl border p-6 shadow-sm transition-all duration-200 hover:shadow-md focus-visible:ring-2 focus-visible:outline-none"
              >
                <span className="text-foreground text-lg font-semibold">
                  IRC Channel
                </span>
                <p className="text-muted-foreground mt-1 text-base">
                  Chat with the community on #zookeeper at irc.libera.chat.
                </p>
                <ArrowUpRight className="text-muted-foreground group-hover:text-primary absolute top-6 right-6 size-5 transition-colors" />
              </Link>
            </li>
            <li id="security" className="relative p-0">
              <Link
                to="/security"
                className="group border-border/60 bg-card/50 hover:bg-card hover:border-primary/50 focus-visible:ring-primary block rounded-2xl border p-6 shadow-sm transition-all duration-200 hover:shadow-md focus-visible:ring-2 focus-visible:outline-none"
              >
                <span className="text-foreground text-lg font-semibold">
                  Security
                </span>
                <p className="text-muted-foreground mt-1 text-base">
                  Report vulnerabilities and review known CVEs.
                </p>
                <ArrowUpRight className="text-muted-foreground group-hover:text-primary absolute top-6 right-6 size-5 transition-colors" />
              </Link>
            </li>
          </ul>
        </div>
      </div>
    </section>
  );
}
