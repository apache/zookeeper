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

import { describe, it, expect } from "vitest";
import {
  extractField,
  parseDevelopers
} from "../scripts/extract-developers.js";

describe("extract-developers script", () => {
  const createPomXml = (
    developersXml: string
  ) => `<?xml version="1.0" encoding="UTF-8"?>
<project>
  <developers>
${developersXml}
  </developers>
</project>`;

  describe("extractField", () => {
    it("should extract existing field value", () => {
      const block = "<id>johndoe</id><name>John Doe</name>";
      expect(extractField(block, "id")).toBe("johndoe");
      expect(extractField(block, "name")).toBe("John Doe");
    });

    it('should return "-" for missing field', () => {
      const block = "<id>johndoe</id><email>john@example.com</email>";
      expect(extractField(block, "name")).toBe("-");
      expect(extractField(block, "timezone")).toBe("-");
    });

    it("should trim whitespace from field values", () => {
      const block = "<id>  johndoe  </id><name>  John Doe  </name>";
      expect(extractField(block, "id")).toBe("johndoe");
      expect(extractField(block, "name")).toBe("John Doe");
    });

    it("should handle multiline field values", () => {
      const block = "<name>John\nDoe</name>";
      const result = extractField(block, "name");
      expect(result).toContain("John");
      expect(result).toContain("Doe");
    });
  });

  describe("parseDevelopers", () => {
    it("should extract developers with all fields present", () => {
      const pomXml = createPomXml(`    <developer>
      <id>johndoe</id>
      <name>John Doe</name>
      <email>johndoe@apache.org</email>
      <timezone>-8</timezone>
    </developer>
    <developer>
      <id>janedoe</id>
      <name>Jane Doe</name>
      <email>janedoe@apache.org</email>
      <timezone>+1</timezone>
    </developer>`);

      const developers = parseDevelopers(pomXml);

      expect(developers).toHaveLength(2);
      expect(developers[0]).toEqual({
        id: "johndoe",
        name: "John Doe",
        email: "johndoe@apache.org",
        timezone: "-8"
      });
      expect(developers[1]).toEqual({
        id: "janedoe",
        name: "Jane Doe",
        email: "janedoe@apache.org",
        timezone: "+1"
      });
    });

    it("should handle missing name field", () => {
      const pomXml = createPomXml(`    <developer>
      <id>tianjy</id>
      <email>tianjy@apache.org</email>
      <timezone>+8</timezone>
    </developer>`);

      const developers = parseDevelopers(pomXml);

      expect(developers).toHaveLength(1);
      expect(developers[0]).toEqual({
        id: "tianjy",
        name: "-",
        email: "tianjy@apache.org",
        timezone: "+8"
      });
    });

    it("should handle missing email field", () => {
      const pomXml = createPomXml(`    <developer>
      <id>testuser</id>
      <name>Test User</name>
      <timezone>+0</timezone>
    </developer>`);

      const developers = parseDevelopers(pomXml);

      expect(developers).toHaveLength(1);
      expect(developers[0]).toEqual({
        id: "testuser",
        name: "Test User",
        email: "-",
        timezone: "+0"
      });
    });

    it("should handle multiple missing fields", () => {
      const pomXml = createPomXml(`    <developer>
      <id>minimaluser</id>
      <email>minimal@apache.org</email>
    </developer>`);

      const developers = parseDevelopers(pomXml);

      expect(developers).toHaveLength(1);
      expect(developers[0]).toEqual({
        id: "minimaluser",
        name: "-",
        email: "minimal@apache.org",
        timezone: "-"
      });
    });

    it("should handle mixed complete and incomplete developer entries", () => {
      const pomXml = createPomXml(`    <developer>
      <id>complete</id>
      <name>Complete User</name>
      <email>complete@apache.org</email>
      <timezone>+1</timezone>
    </developer>
    <developer>
      <id>incomplete</id>
      <email>incomplete@apache.org</email>
      <timezone>+2</timezone>
    </developer>
    <developer>
      <id>another</id>
      <name>Another User</name>
      <email>another@apache.org</email>
      <timezone>-5</timezone>
    </developer>`);

      const developers = parseDevelopers(pomXml);

      expect(developers).toHaveLength(3);
      expect(developers[0]).toEqual({
        id: "complete",
        name: "Complete User",
        email: "complete@apache.org",
        timezone: "+1"
      });
      expect(developers[1]).toEqual({
        id: "incomplete",
        name: "-",
        email: "incomplete@apache.org",
        timezone: "+2"
      });
      expect(developers[2]).toEqual({
        id: "another",
        name: "Another User",
        email: "another@apache.org",
        timezone: "-5"
      });
    });

    it("should trim whitespace from field values", () => {
      const pomXml = createPomXml(`    <developer>
      <id>  spacey  </id>
      <name>  Spacey User  </name>
      <email>  spacey@apache.org  </email>
      <timezone>  +3  </timezone>
    </developer>`);

      const developers = parseDevelopers(pomXml);

      expect(developers).toHaveLength(1);
      expect(developers[0]).toEqual({
        id: "spacey",
        name: "Spacey User",
        email: "spacey@apache.org",
        timezone: "+3"
      });
    });

    it("should handle fields in different order", () => {
      const pomXml = createPomXml(`    <developer>
      <timezone>+5</timezone>
      <name>Reordered User</name>
      <id>reordered</id>
      <email>reordered@apache.org</email>
    </developer>`);

      const developers = parseDevelopers(pomXml);

      expect(developers).toHaveLength(1);
      expect(developers[0]).toEqual({
        id: "reordered",
        name: "Reordered User",
        email: "reordered@apache.org",
        timezone: "+5"
      });
    });

    it("should handle empty developers section", () => {
      const pomXml = createPomXml("");

      const developers = parseDevelopers(pomXml);
      expect(developers).toHaveLength(0);
    });

    it("should throw when developers section is missing", () => {
      expect(() => parseDevelopers("<project></project>")).toThrow(
        /No developers section found/
      );
    });

    it("should handle special characters in field values", () => {
      const pomXml = createPomXml(`    <developer>
      <id>special&amp;user</id>
      <name>User &lt;Name&gt;</name>
      <email>user@example.org</email>
      <timezone>+0</timezone>
    </developer>`);

      const developers = parseDevelopers(pomXml);

      expect(developers).toHaveLength(1);
      expect(developers[0].id).toBe("special&amp;user");
      expect(developers[0].name).toBe("User &lt;Name&gt;");
    });
  });
});
