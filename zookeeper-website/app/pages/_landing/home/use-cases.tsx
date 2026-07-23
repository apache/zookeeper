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
  Crown,
  Database,
  GitBranch,
  Lock,
  Network,
  Waypoints
} from "lucide-react";

export function UseCasesSection() {
  const items = [
    {
      title: "Leader Election",
      desc: "Elect a single master among distributed workers reliably using ephemeral znodes and watches.",
      Icon: Crown
    },
    {
      title: "Distributed Locking",
      desc: "Implement mutexes and read/write locks across cluster nodes without a single point of failure.",
      Icon: Lock
    },
    {
      title: "Service Discovery",
      desc: "Register and look up live service instances dynamically as nodes join or leave the cluster.",
      Icon: Network
    },
    {
      title: "Configuration Management",
      desc: "Propagate config changes to all nodes instantly with watches — no polling required.",
      Icon: Database
    },
    {
      title: "Cluster Membership",
      desc: "Track which nodes are alive in real time using ephemeral nodes and group membership recipes.",
      Icon: Waypoints
    },
    {
      title: "Barrier Synchronization",
      desc: "Coordinate phased computations so all workers start and finish a phase together.",
      Icon: GitBranch
    }
  ];
  return (
    <section id="use-cases" className="border-border/60 bg-muted/20 border-y">
      <div className="container mx-auto px-4 py-16 md:py-24">
        <div className="mx-auto mb-12 max-w-3xl text-center">
          <h2 className="text-3xl font-bold tracking-tight md:text-4xl">
            Use Cases
          </h2>
          <p className="text-muted-foreground mt-4 text-lg">
            Common patterns and distributed systems problems where ZooKeeper
            excels.
          </p>
        </div>
        <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
          {items.map(({ title, desc, Icon }) => (
            <div
              key={title}
              className="border-border/60 bg-card/80 rounded-2xl border p-6 shadow-sm"
            >
              <div className="flex items-start gap-4">
                <div className="bg-background border-border/50 shrink-0 rounded-lg border p-2.5 shadow-sm">
                  <Icon className="text-primary size-6" aria-hidden />
                </div>
                <div>
                  <h3 className="text-foreground text-lg font-semibold">
                    {title}
                  </h3>
                  <p className="text-muted-foreground mt-2 text-sm leading-relaxed">
                    {desc}
                  </p>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
