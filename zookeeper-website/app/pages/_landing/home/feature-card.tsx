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

import type React from "react";
import { cn } from "@/lib/utils";
import type { LucideIcon } from "lucide-react";

export function FeatureCard({
  title,
  children,
  icon: Icon,
  className
}: {
  title: string;
  children: React.ReactNode;
  icon?: LucideIcon;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "border-border/60 bg-card/50 rounded-2xl border p-8 text-left shadow-sm backdrop-blur-sm",
        className
      )}
    >
      {Icon && (
        <div className="bg-primary/10 text-primary mb-5 inline-flex h-12 w-12 items-center justify-center rounded-xl">
          <Icon className="h-6 w-6" />
        </div>
      )}
      <h3 className="text-foreground mb-3 text-xl font-semibold">{title}</h3>
      <p className="text-muted-foreground text-base leading-relaxed">
        {children}
      </p>
    </div>
  );
}
