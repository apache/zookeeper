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
import { Link } from "@/components/link";
import { Terminal } from "lucide-react";

export function Hero() {
  return (
    <section className="relative overflow-hidden">
      {/* Background pattern */}
      <div className="absolute inset-0 -z-10 bg-[linear-gradient(to_right,#80808012_1px,transparent_1px),linear-gradient(to_bottom,#80808012_1px,transparent_1px)] [mask-image:radial-gradient(ellipse_60%_50%_at_50%_0%,#000_70%,transparent_100%)] bg-[size:24px_24px]"></div>

      <div className="container mx-auto px-4 pt-24 pb-16 md:pt-32 md:pb-24">
        <div className="grid grid-cols-1 items-center gap-12 lg:grid-cols-2 lg:gap-8">
          <div className="flex flex-col items-center text-center lg:items-start lg:text-left">
            <img
              src="/images/large-logo.svg"
              alt="Apache ZooKeeper logo"
              width={350}
              height={189}
              className="mb-6 px-4 sm:px-0 lg:px-0 dark:hidden"
            />
            <img
              src="/images/dark-theme-large-logo.svg"
              alt="Apache ZooKeeper logo"
              width={350}
              height={189}
              className="mb-6 hidden px-4 sm:px-0 lg:px-0 dark:block"
            />

            <h1 className="mt-2 text-4xl font-extrabold tracking-tight text-balance md:text-5xl lg:text-6xl">
              The standard for{" "}
              <span className="text-primary">distributed coordination</span>
            </h1>
            <p className="text-muted-foreground mt-6 max-w-2xl text-lg text-pretty md:text-xl">
              Naming, configuration management, synchronization, and group
              services — battle-tested coordination primitives in a simple,
              highly reliable interface.
            </p>

            <div className="mt-8 flex flex-wrap items-center justify-center gap-4 lg:justify-start">
              <Button asChild size="lg">
                <Link to="/releases">Download</Link>
              </Button>
              <Button asChild size="lg" variant="outline">
                <Link to="/docs">Read Documentation</Link>
              </Button>
            </div>
          </div>

          <div className="relative hidden lg:block">
            <div className="from-primary to-primary/50 absolute -inset-1 rounded-2xl bg-gradient-to-r opacity-20 blur"></div>
            <div className="border-border/60 bg-card/80 relative overflow-hidden rounded-2xl border shadow-2xl backdrop-blur-xl">
              <div className="border-border/60 bg-muted/50 flex items-center border-b px-4 py-3">
                <div className="flex space-x-2">
                  <div className="bg-destructive/80 h-3 w-3 rounded-full"></div>
                  <div className="h-3 w-3 rounded-full bg-yellow-500/80"></div>
                  <div className="h-3 w-3 rounded-full bg-green-500/80"></div>
                </div>
                <div className="text-muted-foreground mx-auto flex items-center font-mono text-xs">
                  <Terminal className="mr-2 h-3 w-3" />
                  zkCli.sh
                </div>
              </div>
              <div className="text-muted-foreground overflow-x-auto p-6 font-mono text-sm">
                <div className="text-foreground">
                  $ bin/zkCli.sh -server 127.0.0.1:2181
                </div>
                <div className="mt-2">Connecting to 127.0.0.1:2181</div>
                <div>Welcome to ZooKeeper!</div>
                <div>JLine support is enabled</div>
                <div className="text-foreground mt-2">
                  [zk: 127.0.0.1:2181(CONNECTED) 0] create /myapp "config_data"
                </div>
                <div>Created /myapp</div>
                <div className="text-foreground mt-2">
                  [zk: 127.0.0.1:2181(CONNECTED) 1] get /myapp
                </div>
                <div>config_data</div>
                <div className="text-foreground mt-2">
                  [zk: 127.0.0.1:2181(CONNECTED) 2] ls /
                </div>
                <div>[myapp, zookeeper]</div>
                <div className="text-foreground mt-2 flex items-center">
                  [zk: 127.0.0.1:2181(CONNECTED) 3]{" "}
                  <span className="bg-primary ml-1 h-4 w-2 animate-pulse"></span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
