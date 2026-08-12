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

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

/**
 * Extracts a field value from a developer XML block
 * @param {string} block - The XML block containing developer information
 * @param {string} fieldName - The name of the field to extract
 * @returns {string} The field value or '-' if not present
 */
export function extractField(block, fieldName) {
  const fieldRegex = new RegExp(`<${fieldName}>(.*?)<\/${fieldName}>`, 's');
  const fieldMatch = block.match(fieldRegex);
  return fieldMatch ? fieldMatch[1].trim() : '-';
}

/**
 * Parses developers from POM XML content
 * @param {string} pomContent - The content of the pom.xml file
 * @returns {Array<Object>} Array of developer objects
 * @throws {Error} If no developers section is found
 */
export function parseDevelopers(pomContent) {
  // Extract developers using regex
  const developersMatch = pomContent.match(/<developers>([\s\S]*?)<\/developers>/);
  if (!developersMatch) {
    throw new Error('No developers section found in pom.xml');
  }

  const developersXml = developersMatch[1];
  // Match each developer block
  const developerBlockRegex = /<developer>([\s\S]*?)<\/developer>/gs;

  const developers = [];
  let match;

  while ((match = developerBlockRegex.exec(developersXml)) !== null) {
    const block = match[1];

    const id = extractField(block, 'id');
    const name = extractField(block, 'name');
    const email = extractField(block, 'email');
    const timezone = extractField(block, 'timezone');

    developers.push({
      id,
      name,
      email,
      timezone,
    });
  }

  return developers;
}

/**
 * Main function to extract developers and write to JSON file
 */
export function main() {
  const __filename = fileURLToPath(import.meta.url);
  const __dirname = path.dirname(__filename);

  // Read the parent pom.xml
  const pomPath = path.join(__dirname, '..', '..', 'pom.xml');
  const pomContent = fs.readFileSync(pomPath, 'utf-8');

  let developers;
  try {
    developers = parseDevelopers(pomContent);
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }

  console.log(`Extracted ${developers.length} developers from pom.xml`);

  // Write to JSON file
  const outputPath = path.join(
    __dirname,
    '..',
    'app',
    'pages',
    '_landing',
    'credits',
    'developers.json',
  );
  fs.writeFileSync(outputPath, JSON.stringify(developers, null, 2));

  console.log(`Developers data written to ${outputPath}`);
}

// Run main if this file is executed directly
if (import.meta.url === `file://${process.argv[1]}`) {
  main();
}
