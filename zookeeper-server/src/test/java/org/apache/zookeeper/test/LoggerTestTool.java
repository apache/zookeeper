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
import java.io.IOException;
import java.io.LineNumberReader;
import java.io.StringReader;
import org.slf4j.LoggerFactory;

public class LoggerTestTool implements AutoCloseable {
  private final ByteArrayOutputStream os;
  private Appender<ILoggingEvent> appender;
  private Logger qlogger;
  private Level logLevel = Level.INFO;

  public LoggerTestTool(Class<?> cls) {
    os = createLoggingStream(cls);
  }

  public LoggerTestTool(Class<?> cls, Level logLevel) {
    this.logLevel = logLevel;
    this.os = createLoggingStream(cls);
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
    qlogger.setLevel(logLevel);
    appender.start();
    return os;
  }

  private ByteArrayOutputStream createLoggingStream(String cls) {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    appender = getConsoleAppender(os);
    qlogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(cls);
    qlogger.addAppender(appender);
    qlogger.setLevel(logLevel);
    appender.start();
    return os;
  }

  private OutputStreamAppender<ILoggingEvent> getConsoleAppender(ByteArrayOutputStream os) {
    Logger rootLogger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    rootLogger.setLevel(logLevel);
    Layout<ILoggingEvent> layout = ((LayoutWrappingEncoder<ILoggingEvent>)
        ((OutputStreamAppender<ILoggingEvent>) rootLogger.getAppender("CONSOLE")).getEncoder()).getLayout();

    OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
    appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    appender.setOutputStream(os);
    appender.setLayout(layout);

    return appender;
  }

  public String readLogLine(String search) throws IOException {
    try {
      LineNumberReader r = new LineNumberReader(new StringReader(os.toString()));
      String line;
      while ((line = r.readLine()) != null) {
        if (line.contains(search)) {
          return line;
        }
      }
      return null;
    } finally {
      os.reset();
    }
  }

  @Override
  public void close() throws Exception {
    qlogger.detachAppender(appender);
    os.close();
  }
}
