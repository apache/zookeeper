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

export function IntegrationsSection() {
  const items = [
    {
      title: "Hadoop Ecosystem",
      desc: "Runs on HDFS and integrates with MapReduce, Spark, and other ecosystem tools."
    },
    {
      title: "Observability",
      desc: "Metrics via Hadoop metrics subsystem, JMX export, and production-ready operations."
    },
    {
      title: "Access Layers",
      desc: "Client access via Java API, REST, and Thrift gateways for flexible integration."
    }
  ];
  return (
    <section
      id="integrations"
      className="container mx-auto px-4 py-12 md:py-16"
    >
      <div className="mb-8 text-center">
        <h2 className="text-3xl font-semibold tracking-tight">
          Built to Integrate
        </h2>
        <p className="text-muted-foreground mt-2">
          Designed to fit naturally within your Hadoop-based data platform.
        </p>
      </div>
      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        {items.map((item) => (
          <div
            key={item.title}
            className="border-border/60 bg-card rounded-xl border p-5 shadow-sm"
          >
            <h3 className="text-foreground mb-2 text-lg font-semibold">
              {item.title}
            </h3>
            <p className="text-muted-foreground text-sm leading-6">
              {item.desc}
            </p>
          </div>
        ))}
      </div>
    </section>
  );
}
