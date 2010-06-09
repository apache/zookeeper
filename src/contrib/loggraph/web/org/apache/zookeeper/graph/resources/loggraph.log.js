/**
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

LogGraph.LogTable = function (asyncq, canvas, starttime, endtime, filter) {
    this.starttime = starttime;
    this.endtime = endtime;
    this.filter = filter;

    var table = document.createElement("table");
    table.id = "logtable";
    canvas.appendChild(table);

    this.addLogLine = function(time, text) {
	var tr = document.createElement("tr");
	table.appendChild(tr);
	
	var td = document.createElement("td");
	td.innerHTML = dateFormat(time, "h:MM:ss,l");
	tr.appendChild(td);

	td = document.createElement("td");
	td.innerHTML = text;
	tr.appendChild(td);
    }

    var self = this;
    var processdata = function(data) {
	var events = data["events"];
	var count = 0;
	for (var i in events) {
	    var e = events[i];
	    if (e.type == "text") {
		self.addLogLine(e.time, e.text);
		count++;
	    }
	}
	return count != 0;
    };

    var uri = "/data?start=" + self.starttime + "&end=" + self.endtime + "&filter=" + self.filter; 
    LogGraph.loadData(asyncq, uri, processdata);
};
