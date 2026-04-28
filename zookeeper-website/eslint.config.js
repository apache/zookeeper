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

import js from "@eslint/js";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import tseslint from "typescript-eslint";
import { defineConfig, globalIgnores } from "eslint/config";
import importPlugin from "eslint-plugin-import";
import prettier from "eslint-plugin-prettier";

// Custom rule to enforce Apache License header
const apacheLicenseRule = {
  meta: {
    type: "problem",
    docs: {
      description: "Enforce Apache License header in source files"
    },
    fixable: "code",
    messages: {
      missingHeader: "Missing Apache License header at the top of the file"
    }
  },
  create(context) {
    const REQUIRED_HEADER = `//
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
//`;

    return {
      Program(node) {
        const sourceCode = context.sourceCode || context.getSourceCode();
        const text = sourceCode.getText();
        
        if (!text.startsWith(REQUIRED_HEADER)) {
          context.report({
            node,
            messageId: "missingHeader",
            fix(fixer) {
              return fixer.insertTextBefore(node, REQUIRED_HEADER + "\n\n");
            }
          });
        }
      }
    };
  }
};

// Custom rule to prevent importing Link from react-router
const noReactRouterLinkRule = {
  meta: {
    type: "error",
    docs: {
      description: "Prevent importing Link from react-router, use custom Link component from @/components/link instead"
    },
    messages: {
      noReactRouterLink: "Do not import Link from 'react-router'. Use the custom Link component from '@/components/link' instead. The custom Link component handles hard reloads for external pages that are not part of this React Router app."
    }
  },
  create(context) {
    return {
      ImportDeclaration(node) {
        // Allow the custom Link component file itself to import from react-router
        const filename = context.getFilename();
        if (filename.endsWith('components/link.tsx')) {
          return;
        }
        
        // Check if importing from 'react-router'
        if (node.source.value === 'react-router') {
          // Check if any of the imported specifiers is 'Link'
          const linkImport = node.specifiers.find(
            specifier => 
              (specifier.type === 'ImportSpecifier' && specifier.imported.name === 'Link')
          );
          
          if (linkImport) {
            context.report({
              node: linkImport,
              messageId: "noReactRouterLink"
            });
          }
        }
      }
    };
  }
};

export default defineConfig([
  globalIgnores([
    "node_modules",
    "build",
    "dist",
    "tsconfig.json",
    "prettier.config.js",
    "react-router.config.ts",
    ".react-router",
    ".source",
    "public/released-docs"
  ]),
  {
    files: ["**/*.{ts,tsx}"],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs["recommended-latest"],
    ],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
    plugins: {
      prettier,
      import: importPlugin,
      custom: {
        rules: {
          "apache-license": apacheLicenseRule,
          "no-react-router-link": noReactRouterLinkRule
        }
      }
    },
    settings: {
      // so import/no-unresolved understands TS paths and "@/*"
      "import/resolver": {
        typescript: {
          project: ['./tsconfig.app.json', './tsconfig.node.json'],
        },
      },
    },
    rules: {
      // virtual: modules are resolved by Vite plugins at build/test time
      "import/no-unresolved": ["error", { ignore: ["^virtual:"] }],
      "import/no-duplicates": "warn",

      "no-implicit-globals": "off",
      "no-empty-pattern": "off",

      "@typescript-eslint/no-unused-vars": "warn",
      "@typescript-eslint/ban-ts-comment": "off",
      "@typescript-eslint/no-explicit-any": "off",

      "react/react-in-jsx-scope": "off",
      "react/prop-types": "off",
      "react/no-unescaped-entities": "off",

      "prettier/prettier": "error",
      
      // Enforce Apache License header
      "custom/apache-license": "error",
      
      // Prevent importing Link from react-router
      "custom/no-react-router-link": "error",
    },
  },
]);
