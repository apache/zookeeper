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

import { cn } from "@/lib/utils";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger
} from "@/ui/dropdown-menu";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger
} from "@/ui/collapsible";
import { ChevronDown, ChevronRight, ExternalLink } from "lucide-react";
import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { Link } from "@/components/link";
import { asfLinks, documentationLinks, projectLinks } from "./links";
import { OlderDocsVersionList } from "@/components/docs/older-docs-picker";
import { ThemeToggle } from "./theme-toggle";

const navLinkClass =
  "text-sm font-medium text-foreground/70 hover:text-foreground transition-colors";

export function SiteNavbar() {
  const [isScrolled, setIsScrolled] = useState(false);

  useEffect(() => {
    if (typeof window === "undefined") {
      return;
    }

    document.documentElement.classList.add("js");

    setIsScrolled(window.scrollY > 0);
    const handleScroll = () => {
      setIsScrolled(window.scrollY > 0);
    };

    document.addEventListener("scroll", handleScroll);

    return () => {
      document.removeEventListener("scroll", handleScroll);
    };
  }, []);

  return (
    <header
      className={cn(
        isScrolled ? "border-border/60 shadow-sm" : "border-transparent",
        "bg-background/80 supports-[backdrop-filter]:bg-background/60 sticky top-0 z-50 w-full border-b backdrop-blur-md transition-all duration-300"
      )}
    >
      <nav className="container mx-auto flex h-16 items-center justify-between px-4">
        <Link
          to="/"
          className="relative z-50 flex items-center gap-2 transition-opacity hover:opacity-90"
          aria-label="ZooKeeper Home"
        >
          <img
            src="/images/logo.svg"
            alt="Apache ZooKeeper logo"
            width={36}
            height={36}
          />
          <span className="hidden text-lg font-medium tracking-tight sm:inline-block">
            ZooKeeper
          </span>
        </Link>

        {/* Desktop menus */}
        <div className="hidden items-center gap-4 md:flex">
          <div className="js:hidden">
            <NoJSProjectMenu />
          </div>
          <div className="js:block hidden">
            <ProjectMenu />
          </div>

          <div className="js:hidden">
            <NoJSDocsMenu />
          </div>
          <div className="js:block hidden">
            <DocsMenu />
          </div>

          <div className="js:hidden">
            <NoJSAsfMenu />
          </div>
          <div className="js:block hidden">
            <AsfMenu />
          </div>

          <div className="theme-toggle-wrapper">
            <ThemeToggle />
          </div>
        </div>

        {/* Mobile menu */}
        <div className="flex items-center gap-2 md:hidden">
          <div className="theme-toggle-wrapper">
            <ThemeToggle />
          </div>

          <div className="js:hidden">
            <NoJSMobileMenu />
          </div>
          <div className="js:block hidden">
            <MobileMenu />
          </div>
        </div>
      </nav>
    </header>
  );
}

function ProjectMenu() {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          className={`${navLinkClass} inline-flex cursor-pointer items-center`}
        >
          Apache ZooKeeper Project <ChevronDown className="ml-1 h-4 w-4" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="center">
        {projectLinks.map((item) => (
          <DropdownMenuItem key={item.label} asChild>
            <Link to={item.to} target={item.external ? "_blank" : "_self"}>
              {item.label}
              {item.external && <ExternalLink className="size-4" />}
            </Link>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function OlderDocsSubMenu() {
  return (
    <DropdownMenuSub>
      <DropdownMenuSubTrigger>Older docs</DropdownMenuSubTrigger>
      <DropdownMenuSubContent className="w-56 p-0">
        <OlderDocsVersionList />
      </DropdownMenuSubContent>
    </DropdownMenuSub>
  );
}

function DocsMenu() {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          className={`${navLinkClass} inline-flex cursor-pointer items-center`}
        >
          Documentation <ChevronDown className="ml-1 h-4 w-4" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="center">
        {documentationLinks.map((item) =>
          "to" in item ? (
            <DropdownMenuItem key={item.label} asChild>
              <Link
                to={item.to}
                target={item.external ? "_blank" : "_self"}
                aria-label={item.label}
              >
                {item.label}
                {item.external && <ExternalLink className="size-4" />}
              </Link>
            </DropdownMenuItem>
          ) : (
            <DropdownMenuSub key={item.label}>
              <DropdownMenuSubTrigger>{item.label}</DropdownMenuSubTrigger>
              <DropdownMenuSubContent>
                {item.links.map((item) => (
                  <DropdownMenuItem key={item.label} asChild>
                    <Link
                      to={item.to}
                      target={item.external ? "_blank" : "_self"}
                      aria-label={item.label}
                    >
                      {item.label}
                      {item.external && <ExternalLink className="size-4" />}
                    </Link>
                  </DropdownMenuItem>
                ))}
              </DropdownMenuSubContent>
            </DropdownMenuSub>
          )
        )}
        <DropdownMenuSeparator />
        <OlderDocsSubMenu />
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function AsfMenu() {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button className={`${navLinkClass} inline-flex cursor-pointer`}>
          ASF <ChevronDown className="ml-1 h-4 w-4" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="center">
        {asfLinks.map((item) => (
          <DropdownMenuItem key={item.label} asChild>
            <Link
              to={item.to}
              target={item.external ? "_blank" : "_self"}
              aria-label={item.label}
            >
              {item.label}
              {item.external && <ExternalLink className="size-4" />}
            </Link>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function MobileMenu() {
  const [isOpen, setIsOpen] = useState(false);
  const [isMounted, setIsMounted] = useState(false);

  useEffect(() => {
    setIsMounted(true);
  }, []);

  useEffect(() => {
    if (isOpen) {
      document.body.style.overflow = "hidden";
    } else {
      document.body.style.overflow = "unset";
    }
    return () => {
      document.body.style.overflow = "unset";
    };
  }, [isOpen]);

  useEffect(() => {
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === "Escape" && isOpen) {
        setIsOpen(false);
      }
    };

    document.addEventListener("keydown", handleEscape);
    return () => document.removeEventListener("keydown", handleEscape);
  }, [isOpen]);

  useEffect(() => {
    const mediaQuery = window.matchMedia("(min-width: 768px)");
    const handleMediaChange = (e: MediaQueryListEvent | MediaQueryList) => {
      if (e.matches && isOpen) {
        setIsOpen(false);
      }
    };

    handleMediaChange(mediaQuery);
    mediaQuery.addEventListener("change", handleMediaChange);
    return () => mediaQuery.removeEventListener("change", handleMediaChange);
  }, [isOpen]);

  return (
    <>
      <button
        onClick={() => setIsOpen(!isOpen)}
        className="text-foreground relative z-50 p-2"
        aria-label={isOpen ? "Close menu" : "Open menu"}
      >
        <div className="flex h-5 w-5 flex-col items-center justify-center">
          <span
            className={cn(
              "bg-foreground absolute h-0.5 w-5 transition-all duration-300",
              isOpen ? "rotate-45" : "-translate-y-1.5"
            )}
          />
          <span
            className={cn(
              "bg-foreground h-0.5 w-5 transition-all duration-300",
              isOpen ? "opacity-0" : "opacity-100"
            )}
          />
          <span
            className={cn(
              "bg-foreground absolute h-0.5 w-5 transition-all duration-300",
              isOpen ? "-rotate-45" : "translate-y-1.5"
            )}
          />
        </div>
      </button>

      {/* Fullscreen overlay - rendered in portal */}
      {isMounted &&
        createPortal(
          <div
            className={cn(
              "bg-background fixed inset-0 z-40 overflow-y-auto transition-all duration-300",
              isOpen
                ? "pointer-events-auto opacity-100"
                : "pointer-events-none opacity-0"
            )}
          >
            <div className="px-6 pt-24 pb-8">
              <nav className="space-y-4">
                <MobileMenuSection
                  title="Apache ZooKeeper Project"
                  links={projectLinks}
                  onLinkClick={() => setIsOpen(false)}
                />
                <MobileDocsSection onLinkClick={() => setIsOpen(false)} />
                <MobileMenuSection
                  title="ASF"
                  links={asfLinks}
                  onLinkClick={() => setIsOpen(false)}
                />
              </nav>
            </div>
          </div>,
          document.body
        )}
    </>
  );
}

function MobileOlderDocsSection() {
  return (
    <Collapsible className="w-full">
      <CollapsibleTrigger className="text-muted-foreground hover:text-foreground flex w-full items-center justify-between py-1.5 text-left text-sm">
        Older docs
        <ChevronRight className="h-3 w-3 rotate-90 transition-transform group-data-[state=closed]:rotate-0" />
      </CollapsibleTrigger>
      <CollapsibleContent className="w-full pt-1 pl-3">
        <OlderDocsVersionList />
      </CollapsibleContent>
    </Collapsible>
  );
}

function MobileDocsSection({ onLinkClick }: { onLinkClick: () => void }) {
  return (
    <Collapsible className="w-full">
      <CollapsibleTrigger className="flex w-full items-center justify-between py-2 text-left font-medium">
        Documentation
        <ChevronRight className="h-4 w-4 rotate-90 transition-transform group-data-[state=closed]:rotate-0" />
      </CollapsibleTrigger>
      <CollapsibleContent className="w-full space-y-2 pl-4">
        {documentationLinks.map((link) =>
          "to" in link ? (
            <Link
              key={link.label}
              to={link.to}
              target={link.external ? "_blank" : "_self"}
              onClick={onLinkClick}
              className="text-muted-foreground hover:text-foreground flex items-center py-1.5 text-sm"
            >
              {link.label}
              {link.external && <ExternalLink className="ml-1 h-3 w-3" />}
            </Link>
          ) : (
            <Collapsible key={link.label} className="w-full">
              <CollapsibleTrigger className="text-muted-foreground hover:text-foreground flex w-full items-center justify-between py-1.5 text-left text-sm">
                {link.label}
                <ChevronRight
                  className={cn(
                    "h-3 w-3 rotate-90 transition-transform group-data-[state=closed]:rotate-0"
                  )}
                />
              </CollapsibleTrigger>
              <CollapsibleContent className="w-full space-y-1 pl-3">
                {link.links.map((item) => (
                  <Link
                    key={item.label}
                    to={item.to}
                    target={item.external ? "_blank" : "_self"}
                    className="text-muted-foreground hover:text-foreground flex items-center py-1 text-xs"
                  >
                    {item.label}
                  </Link>
                ))}
              </CollapsibleContent>
            </Collapsible>
          )
        )}
        <MobileOlderDocsSection />
      </CollapsibleContent>
    </Collapsible>
  );
}

function MobileMenuSection({
  title,
  links,
  onLinkClick
}: {
  title: string;
  links: typeof projectLinks;
  onLinkClick: () => void;
}) {
  const [isOpen, setIsOpen] = useState(false);

  return (
    <Collapsible open={isOpen} onOpenChange={setIsOpen} className="w-full">
      <CollapsibleTrigger className="flex w-full items-center justify-between py-2 text-left font-medium">
        {title}
        <ChevronRight
          className={cn("h-4 w-4 transition-transform", isOpen && "rotate-90")}
        />
      </CollapsibleTrigger>
      <CollapsibleContent className="w-full space-y-2 pl-4">
        {links.map((link) => (
          <Link
            key={link.label}
            to={link.to}
            target={link.external ? "_blank" : "_self"}
            onClick={onLinkClick}
            className="text-muted-foreground hover:text-foreground flex items-center py-1.5 text-sm"
          >
            {link.label}
            {link.external && <ExternalLink className="ml-1 h-3 w-3" />}
          </Link>
        ))}
      </CollapsibleContent>
    </Collapsible>
  );
}

/* No-JS fallback components using details/summary */

function NoJSProjectMenu() {
  return (
    <details className="group relative">
      <summary
        className={`${navLinkClass} inline-flex cursor-pointer list-none items-center`}
      >
        Apache ZooKeeper Project <ChevronDown className="ml-1 h-4 w-4" />
      </summary>
      <div className="bg-popover text-popover-foreground absolute top-full left-1/2 z-50 mt-1.5 min-w-[12rem] -translate-x-1/2 rounded-md border p-1 shadow-md">
        {projectLinks.map((item) => (
          <Link
            key={item.label}
            to={item.to}
            target={item.external ? "_blank" : "_self"}
            className="hover:bg-accent hover:text-accent-foreground relative flex cursor-pointer items-center rounded-sm px-2 py-1.5 text-sm outline-none select-none"
          >
            {item.label}
            {item.external && <ExternalLink className="size-4" />}
          </Link>
        ))}
      </div>
    </details>
  );
}

function NoJSDocsMenu() {
  return (
    <details className="group relative">
      <summary
        className={`${navLinkClass} inline-flex cursor-pointer list-none items-center`}
      >
        Documentation <ChevronDown className="ml-1 h-4 w-4" />
      </summary>
      <div className="bg-popover text-popover-foreground absolute top-full left-1/2 z-50 mt-1.5 min-w-[12rem] -translate-x-1/2 rounded-md border p-1 shadow-md">
        {documentationLinks.map((item) =>
          "to" in item ? (
            <Link
              key={item.label}
              to={item.to}
              target={item.external ? "_blank" : "_self"}
              aria-label={item.label}
              className="hover:bg-accent hover:text-accent-foreground relative flex cursor-pointer items-center rounded-sm px-2 py-1.5 text-sm outline-none select-none"
            >
              {item.label}
              {item.external && <ExternalLink className="size-4" />}
            </Link>
          ) : (
            <details key={item.label} className="group/sub relative">
              <summary className="hover:bg-accent hover:text-accent-foreground flex cursor-pointer items-center justify-between rounded-sm px-2 py-1.5 text-sm outline-none select-none">
                {item.label}
                <ChevronRight className="ml-2 h-4 w-4" />
              </summary>
              <div className="bg-popover text-popover-foreground absolute top-0 left-full z-50 ml-1 min-w-[12rem] rounded-md border p-1 shadow-md">
                {item.links.map((subItem) => (
                  <Link
                    key={subItem.label}
                    to={subItem.to}
                    target={subItem.external ? "_blank" : "_self"}
                    aria-label={subItem.label}
                    className="hover:bg-accent hover:text-accent-foreground relative flex cursor-pointer items-center rounded-sm px-2 py-1.5 text-sm outline-none select-none"
                  >
                    {subItem.label}
                    {subItem.external && <ExternalLink className="size-4" />}
                  </Link>
                ))}
              </div>
            </details>
          )
        )}
      </div>
    </details>
  );
}

function NoJSAsfMenu() {
  return (
    <details className="group relative">
      <summary
        className={`${navLinkClass} inline-flex cursor-pointer list-none`}
      >
        ASF <ChevronDown className="ml-1 h-4 w-4" />
      </summary>
      <div className="bg-popover text-popover-foreground absolute top-full left-1/2 z-50 mt-1.5 min-w-[12rem] -translate-x-1/2 rounded-md border p-1 shadow-md">
        {asfLinks.map((item) => (
          <Link
            key={item.label}
            to={item.to}
            target={item.external ? "_blank" : "_self"}
            aria-label={item.label}
            className="hover:bg-accent hover:text-accent-foreground relative flex cursor-pointer items-center rounded-sm px-2 py-1.5 text-sm outline-none select-none"
          >
            {item.label}
            {item.external && <ExternalLink className="size-4" />}
          </Link>
        ))}
      </div>
    </details>
  );
}

function NoJSMobileMenu() {
  return (
    <details className="group relative z-50">
      <summary className="text-foreground relative flex h-10 w-10 cursor-pointer list-none items-center justify-center p-2">
        <div className="flex h-5 w-5 flex-col items-center justify-center">
          <span className="bg-foreground absolute h-0.5 w-5 -translate-y-1.5" />
          <span className="bg-foreground h-0.5 w-5" />
          <span className="bg-foreground absolute h-0.5 w-5 translate-y-1.5" />
        </div>
      </summary>
      <div className="bg-background fixed inset-x-0 top-16 z-40 max-h-[calc(100vh-4rem)] overflow-y-auto border-t p-6">
        <nav className="space-y-4">
          <NoJSMobileMenuSection
            title="Apache ZooKeeper Project"
            links={projectLinks}
          />
          <NoJSMobileDocsSection />
          <NoJSMobileMenuSection title="ASF" links={asfLinks} />
        </nav>
      </div>
    </details>
  );
}

function NoJSMobileMenuSection({
  title,
  links
}: {
  title: string;
  links: typeof projectLinks;
}) {
  return (
    <details className="w-full">
      <summary className="flex w-full cursor-pointer items-center justify-between py-2 text-left font-medium">
        {title}
        <ChevronRight className="h-4 w-4" />
      </summary>
      <div className="w-full space-y-2 pt-2 pl-4">
        {links.map((link) => (
          <Link
            key={link.label}
            to={link.to}
            target={link.external ? "_blank" : "_self"}
            className="text-muted-foreground hover:text-foreground flex cursor-pointer items-center py-1.5 text-sm"
          >
            {link.label}
            {link.external && <ExternalLink className="ml-1 h-3 w-3" />}
          </Link>
        ))}
      </div>
    </details>
  );
}

function NoJSMobileDocsSection() {
  return (
    <details className="w-full">
      <summary className="flex w-full cursor-pointer items-center justify-between py-2 text-left font-medium">
        Documentation
        <ChevronRight className="h-4 w-4" />
      </summary>
      <div className="w-full space-y-2 pt-2 pl-4">
        {documentationLinks.map((link) =>
          "to" in link ? (
            <Link
              key={link.label}
              to={link.to}
              target={link.external ? "_blank" : "_self"}
              className="text-muted-foreground hover:text-foreground flex cursor-pointer items-center py-1.5 text-sm"
            >
              {link.label}
              {link.external && <ExternalLink className="ml-1 h-3 w-3" />}
            </Link>
          ) : (
            <details key={link.label} className="w-full">
              <summary className="text-muted-foreground hover:text-foreground flex w-full cursor-pointer items-center justify-between py-1.5 text-left text-sm">
                {link.label}
                <ChevronRight className="h-3 w-3" />
              </summary>
              <div className="w-full space-y-1 pt-1 pl-3">
                {link.links.map((item) => (
                  <Link
                    key={item.label}
                    to={item.to}
                    target={item.external ? "_blank" : "_self"}
                    className="text-muted-foreground hover:text-foreground flex cursor-pointer items-center py-1 text-xs"
                  >
                    {item.label}
                  </Link>
                ))}
              </div>
            </details>
          )
        )}
      </div>
    </details>
  );
}
