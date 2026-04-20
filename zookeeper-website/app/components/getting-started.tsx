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

import { Link } from "@/components/link";
import { ArrowRight } from "lucide-react";

export function GettingStartedSection() {
  const steps = [
    {
      title: "1. Download",
      desc: "Grab the latest stable release and verify checksums.",
      to: "/releases"
    },
    {
      title: "2. Quick Start",
      desc: "Set up a single ZooKeeper server and learn the basics of the CLI.",
      to: "/docs/overview/quick-start"
    },
    {
      title: "3. Write a Client",
      desc: "Follow the basic tutorial to implement distributed primitives like barriers and queues.",
      to: "/docs/developer/basic-tutorial"
    }
  ];
  return (
    <section
      id="getting-started"
      className="border-border/60 bg-muted/20 border-y"
    >
      <div className="container mx-auto px-4 py-16 md:py-24">
        <div className="mx-auto mb-12 max-w-3xl text-center">
          <h2 className="text-3xl font-bold tracking-tight md:text-4xl">
            Getting Started
          </h2>
          <p className="text-muted-foreground mt-4 text-lg">
            Up and running with ZooKeeper in a few simple steps.
          </p>
        </div>
        <div className="grid grid-cols-1 gap-6 md:grid-cols-3">
          {steps.map((s) => (
            <Link
              key={s.title}
              to={s.to}
              className="group border-border/60 bg-card/80 hover:bg-card hover:border-primary/50 focus-visible:ring-primary flex flex-col rounded-2xl border p-6 shadow-sm transition-all duration-200 hover:shadow-md focus-visible:ring-2 focus-visible:outline-none"
            >
              <h3 className="text-foreground text-xl font-semibold">
                {s.title}
              </h3>
              <p className="text-muted-foreground mt-2 text-base leading-relaxed">
                {s.desc}
              </p>
              <div className="text-primary mt-auto flex items-center pt-6 text-sm font-medium">
                Learn more{" "}
                <ArrowRight className="ml-1 h-4 w-4 transition-transform group-hover:translate-x-1" />
              </div>
            </Link>
          ))}
        </div>
      </div>
    </section>
  );
}
