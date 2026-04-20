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

import { CURRENT_VERSION } from "@/lib/current-version";

interface LinkType {
  label: string;
  to: string;
  external?: boolean;
}

interface NestedLinkType {
  label: string;
  links: LinkType[];
}

export const projectLinks: LinkType[] = [
  {
    label: "Overview",
    to: "/"
  },
  {
    label: "Releases",
    to: "/releases"
  },
  {
    label: "Events",
    to: "/events"
  },
  {
    label: "News",
    to: "/news"
  },
  {
    label: "Mailing Lists",
    to: "/mailing-lists"
  },
  {
    label: "Credits",
    to: "/credits"
  },
  {
    label: "Bylaws",
    to: "/bylaws"
  }
];

export const documentationLinks: (LinkType | NestedLinkType)[] = [
  {
    label: `${CURRENT_VERSION} Documentation`,
    to: "/docs"
  },
  {
    label: "Issue Tracking",
    to: "https://issues.apache.org/jira/browse/ZOOKEEPER",
    external: true
  },
  {
    label: "Security",
    to: "/security"
  },
  {
    label: "Version Control",
    to: "/version-control"
  },
  {
    label: "Resources",
    links: [
      {
        label: "Wiki",
        to: "https://cwiki.apache.org/confluence/display/ZOOKEEPER",
        external: true
      },
      {
        label: "IRC Channel",
        to: "/irc"
      }
    ]
  }
];

export const asfLinks: LinkType[] = [
  {
    label: "Apache Software Foundation",
    to: "http://www.apache.org/foundation/",
    external: true
  },
  {
    label: "License",
    to: "https://www.apache.org/licenses/",
    external: true
  },
  {
    label: "How Apache Works",
    to: "http://www.apache.org/foundation/how-it-works.html",
    external: true
  },
  {
    label: "Foundation Program",
    to: "http://www.apache.org/foundation/sponsorship.html",
    external: true
  },
  {
    label: "Sponsors",
    to: "https://www.apache.org/foundation/sponsors",
    external: true
  },
  {
    label: "Privacy Policy",
    to: "https://privacy.apache.org/policies/privacy-policy-public.html",
    external: true
  }
];
