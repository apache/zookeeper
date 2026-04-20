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

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architectural test to enforce that Netty is an optional dependency.
 *
 * <p>Only classes whose name contains "Netty" (e.g. {@code NettyServerCnxnFactory},
 * {@code ClientCnxnSocketNetty}, {@code ClientNettyX509Util}) and a small set of
 * explicitly allowed SSL/TLS utility classes ({@code UnifiedServerSocket}) may depend
 * on {@code io.netty} packages. All other ZooKeeper classes must remain Netty-free so
 * that Netty can be an optional dependency for users who do not need SSL/TLS.
 */
public class NettyOptionalArchTest {

    @Test
    public void nonNettyClassesShouldNotDependOnNetty() {
        JavaClasses importedClasses = new ClassFileImporter(
                Collections.singletonList(new ImportOption.DoNotIncludeTests()))
                .importPackages("org.apache.zookeeper")
                .that(new DescribedPredicate<JavaClass>("ZK Non-Netty classes") {
                    @Override
                    public boolean test(JavaClass javaClass) {
                        // Exclude classes with "Netty" in their name (e.g. NettyServerCnxnFactory,
                        // ClientCnxnSocketNetty, NettyServerCnxn, NettyUtils, ClientNettyX509Util).
                        // Also exclude UnifiedServerSocket (and its inner classes) which legitimately
                        // uses the Netty SSL API to detect SSL vs plain-text connections.
                        String name = javaClass.getName();
                        return !name.contains("Netty")
                            && !name.contains("UnifiedServerSocket");
                    }
                });

        ArchRule rule = noClasses().should()
                .dependOnClassesThat().resideInAnyPackage("io.netty..")
                .orShould().dependOnClassesThat().haveSimpleNameContaining("Netty");

        rule.check(importedClasses);
    }
}
