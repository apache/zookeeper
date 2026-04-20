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

import { FeatureCard } from "@/pages/_landing/home/feature-card";
import { Zap, ShieldCheck, ListOrdered, ServerCrash } from "lucide-react";

export function FeaturesSection() {
  const features = [
    {
      title: "High Performance",
      desc: "In-memory messaging with sub-millisecond latency; handles tens of thousands of requests per second.",
      Icon: Zap
    },
    {
      title: "Simple & Reliable",
      desc: "A clean hierarchical namespace modeled after a file system makes coordination logic easy to reason about.",
      Icon: ShieldCheck
    },
    {
      title: "Ordered Operations",
      desc: "Every update is stamped with a monotonically increasing zxid, giving clients a total ordering guarantee.",
      Icon: ListOrdered
    },
    {
      title: "Fault-Tolerant",
      desc: "Replicated across an ensemble of servers; survives node failures automatically with no data loss.",
      Icon: ServerCrash
    }
  ];
  return (
    <section id="features" className="container mx-auto px-4 py-16 md:py-24">
      <div className="mx-auto mb-12 max-w-3xl text-center">
        <h2 className="text-3xl font-bold tracking-tight md:text-4xl">
          Why ZooKeeper
        </h2>
        <p className="text-muted-foreground mt-4 text-lg">
          A proven coordination kernel trusted by Kafka, Hadoop, HBase, and many
          other distributed systems.
        </p>
      </div>
      <div className="grid grid-cols-1 gap-6 sm:grid-cols-2">
        {features.map((f) => (
          <FeatureCard key={f.title} title={f.title} icon={f.Icon}>
            {f.desc}
          </FeatureCard>
        ))}
      </div>
    </section>
  );
}
