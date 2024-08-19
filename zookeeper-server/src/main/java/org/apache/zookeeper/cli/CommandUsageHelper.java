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

package org.apache.zookeeper.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import javax.annotation.Nullable;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;

public class CommandUsageHelper {

    public static String getUsage(String commandSyntax, @Nullable Options options) {
        StringBuilder buffer = new StringBuilder();
        buffer.append(commandSyntax);
        if (options != null && !options.getOptions().isEmpty()) {
            buffer.append(System.lineSeparator());
            StringWriter out = new StringWriter();
            HelpFormatter formatter = new HelpFormatter();
            formatter.printOptions(new PrintWriter(out), formatter.getWidth(), options, formatter.getLeftPadding(),
                    formatter.getDescPadding());
            buffer.append(out);
        }
        return buffer.toString();
    }
}
