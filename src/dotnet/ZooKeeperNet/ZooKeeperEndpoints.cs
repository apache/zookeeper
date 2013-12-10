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

namespace ZooKeeperNet
{
    using System.Text;
    using System.Net;
    using System;
    using System.Collections.Generic;
    using System.Linq;

    public class ZooKeeperEndpoints:IEnumerable<ZooKeeperEndpoint>
    {
        private static readonly TimeSpan defaultBackoffInterval = new TimeSpan(0,2,0); 
        private List<ZooKeeperEndpoint> zkEndpoints = new List<ZooKeeperEndpoint>();
        private ZooKeeperEndpoint endpoint = null;
        private int endpointID = -1;
        private bool isNextEndPointAvailable = true;
        private TimeSpan backoffInterval;

       
        public ZooKeeperEndpoints(List<IPEndPoint> endpoints)
            : this(endpoints, defaultBackoffInterval)
        {
        }

        public ZooKeeperEndpoints(List<IPEndPoint> endpoints, TimeSpan backoffInterval)
        {
            this.backoffInterval = backoffInterval;
            AddRange(endpoints);
        }

        public IEnumerator<ZooKeeperEndpoint> GetEnumerator()
        {
            return this.zkEndpoints.GetEnumerator();
        }

        public void Add(IPEndPoint endPoint)
        {
            this.Add(new ZooKeeperEndpoint(endPoint, backoffInterval));
        }

        public void Add(ZooKeeperEndpoint endPoint)
        {
            zkEndpoints.Add(endPoint);        
        }

        public void AddRange(List<ZooKeeperEndpoint> endPoints)
        {
            this.zkEndpoints.AddRange((IEnumerable<ZooKeeperEndpoint>)endPoints);
        }

        public void AddRange(List<IPEndPoint> endPoints)
        {
            AddRange(endPoints.ConvertAll(e => new ZooKeeperEndpoint(e, backoffInterval)));
        }

        public ZooKeeperEndpoint CurrentEndPoint
        { 
            get { return this.endpoint;}
        }

        public int EndPointID
        {
            get { return this.endpointID; }    
        }

        public bool IsNextEndPointAvailable
        {
            get { return this.isNextEndPointAvailable; }
        }

        public void GetNextAvailableEndpoint()
        {
            isNextEndPointAvailable = true;
            if(this.zkEndpoints.Count > 0)
            {
                int startingPosition = endpointID;
                do
                {
                    endpointID++;
                    if (endpointID == zkEndpoints.Count)
                    {
                        endpointID = 0;
                    }

                    if (endpointID == startingPosition)
                    { 
                        //All connections are disabled.
                        //Try one at a time until success
                        ResetConnections(endpointID);
                    }
                }
                while (this.zkEndpoints[endpointID].NextAvailability > DateTime.Now);
            }

            this.endpoint = zkEndpoints[endpointID];
        }

        private void ResetConnections(int currentPostition)
        {
            for (int i = 0; i < zkEndpoints.Count; i++)
            {
                if (zkEndpoints[i].RetryAttempts > 1)
                {
                    //clear connection
                    zkEndpoints[i].SetAsSuccess();
                    //set back to lowest backoff
                    zkEndpoints[i].SetAsFailure();
                }
            }

            //Enable current connection
            zkEndpoints[endpointID].SetAsSuccess();
            isNextEndPointAvailable = false;
        }

        System.Collections.IEnumerator System.Collections.IEnumerable.GetEnumerator()
        {
            return GetEnumerator();
        }


    }
}
    