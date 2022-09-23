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

package org.apache.zookeeper.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import java.io.ByteArrayOutputStream;
import org.slf4j.LoggerFactory;

public class LoggerTestTool implements AutoCloseable {
  private final ByteArrayOutputStream os;
  private Appender<ILoggingEvent> appender;
  private Logger qlogger;

  public LoggerTestTool(Class<?> cls) {
    os = createLoggingStream(cls);
  }

  public LoggerTestTool(String cls) {
    os = createLoggingStream(cls);
  }

  public ByteArrayOutputStream getOutputStream() {
    return os;
  }

  private ByteArrayOutputStream createLoggingStream(Class<?> cls) {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    appender = getConsoleAppender(os);
    qlogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(cls);
    qlogger.addAppender(appender);
    qlogger.setLevel(Level.INFO);
    appender.start();
    return os;
  }

  private ByteArrayOutputStream createLoggingStream(String cls) {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    appender = getConsoleAppender(os);
    qlogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(cls);
    qlogger.addAppender(appender);
    qlogger.setLevel(Level.INFO);
    appender.start();
    return os;
  }

  private OutputStreamAppender<ILoggingEvent> getConsoleAppender(ByteArrayOutputStream os) {
    Logger rootLogger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    Layout<ILoggingEvent> layout = ((LayoutWrappingEncoder<ILoggingEvent>)
        ((OutputStreamAppender<ILoggingEvent>) rootLogger.getAppender("CONSOLE")).getEncoder()).getLayout();

    OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
    appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    appender.setOutputStream(os);
    appender.setLayout(layout);

    return appender;
  }

  @Override
  public void close() throws Exception {
    qlogger.detachAppender(appender);
    os.close();
  }
}
