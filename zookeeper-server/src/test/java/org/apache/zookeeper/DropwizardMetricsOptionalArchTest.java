/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * Architectural test to enforce that Dropwizard metrics is an optional dependency.
 *
 * <p>Only {@link org.apache.zookeeper.server.metric.AvgMinMaxPercentileCounter} may depend
 * on {@code com.codahale.metrics} packages. All other ZooKeeper classes must remain Dropwizard-free so that it can be
 * an optional dependency.
 */
public class DropwizardMetricsOptionalArchTest {

    @Test
    public void onlyAvgMinMaxPercentileCounterShouldDependOnDropwizard() {
        JavaClasses importedClasses =
            new ClassFileImporter(Collections.singletonList(new ImportOption.DoNotIncludeTests())).importPackages(
                "org.apache.zookeeper").that(new DescribedPredicate<JavaClass>("ZK non-Dropwizard classes") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return !javaClass.getName().contains("AvgMinMaxPercentileCounter");
                }
            });

        ArchRule rule = noClasses().should().dependOnClassesThat().resideInAnyPackage("com.codahale..");

        rule.check(importedClasses);
    }

}
