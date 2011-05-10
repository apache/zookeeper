/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
﻿namespace ZooKeeperNet
{
    using System;
    using Org.Apache.Zookeeper.Proto;

    public class WatchedEvent
    {
        private readonly KeeperState state;
        private readonly EventType type;
        private readonly string path;

        public WatchedEvent(KeeperState state, EventType type, string path)
        {
            this.state = state;
            this.type = type;
            this.path = path;
        }

        public WatchedEvent(WatcherEvent eventMessage)
        {
            state = (KeeperState)Enum.ToObject(typeof(KeeperState), eventMessage.State);
            type = (EventType)Enum.ToObject(typeof (EventType), eventMessage.Type);
            path = eventMessage.Path;
        }

        public KeeperState State
        {
            get { return state; }
        }

        public EventType Type
        {
            get { return type; }
        }

        public string Path
        {
            get { return path; }
        }

        public override string ToString()
        {
            return "WatchedEvent state:" + state
                + " type:" + type + " path:" + path;
        }

        /**
         *  Convert WatchedEvent to type that can be sent over network
         */
        public WatcherEvent GetWrapper()
        {
            return new WatcherEvent((int)type, (int)state, path);
        }
    }
}
