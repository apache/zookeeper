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

LogGraph = function(canvas, status) {
    this.canvas = document.getElementById(canvas);
    this.status = document.getElementById(status);
    this.starttime = 0;
    this.endtime = 0;
    this.period = 0;
    this.numEntries = 0;
    this.currentRender = 0;
    this.filter = "";

    this.saveFilters = function () {
	localStorage.starttime = this.starttime;
	localStorage.endtime = this.endtime;
	localStorage.period = this.period;
	localStorage.filter = this.filter;
	
    };
    this.loadFilters = function () {
	if (localStorage.starttime) { this.starttime = parseInt(localStorage.starttime); }
	if (localStorage.endtime) { this.endtime = parseInt(localStorage.endtime); }
	if (localStorage.period) { this.period = parseInt(localStorage.period); }
	if (localStorage.filter) { this.filter = localStorage.filter; }
    };
    this.loadFilters();
    var self = this;

    var updateStatus = function (starttime, period, filter, numEntries) {
	self.starttime = starttime;
	self.endtime = starttime + period;
	self.period = period;
	self.filter = filter;
	self.saveFilters(); 
       
	self.status.innerHTML = dateFormat(starttime, "HH:MM:ss,l") + " &rArr; " + dateFormat(self.endtime, "HH:MM:ss,l") + " &nbsp;&nbsp;&nbsp;|&nbsp;&nbsp;&nbsp; " + numEntries + " entries  &nbsp;&nbsp;&nbsp;|&nbsp;&nbsp;&nbsp; " +  (filter ? filter : "No filter");
	
	if (self.currentRender) {
	    self.currentRender();
	}
    };
	
    YUI().use("io-base", function(Y) {
	    var uri = "/info";
	    if (self.starttime) {
		var uri = "/info?start=" + self.starttime + "&period=" + self.period + "&filter=" + self.filter;
	    }
	    
	    function complete(id, o, args) {
		var data = eval("(" + o.responseText + ")"); // Response data.
		var period = data.endTime - data.startTime;
		updateStatus(data.startTime, period, self.filter, data.numEntries);
	    };
	    
	    Y.on('io:complete', complete, Y, []);
	    var request = Y.io(uri);
	});
    
    this.addLogs = function() {
	new LogGraph.fileSelector(function (files) { new LogGraph.fileLoader(files); });
    };

    this.editFilters = function() {
	new LogGraph.filterSelector(this.starttime, this.period, this.filter, updateStatus);
    };	
	
    this.getCleanCanvas = function () {
	this.canvas.innerHTML = "";
	return this.canvas;
    };

    this.showLoadingScreen = function () {
	this.loadingScreen = document.createElement("div");
	this.loadingScreen.id = "loadingScreen";
	this.loadingScreen.innerHTML = "<img src=\"load-big.gif\" /> <p>Loading...</p>";
	document.body.appendChild(this.loadingScreen);
    };

    this.hideLoadingScreen = function () {
	document.body.removeChild(this.loadingScreen);
	this.loadingScreen.style.visibility = "hidden";
    };


    /***
     * TODO: refactor these to load the data first, before handing to a draw funciton. 
     *       We shouldn't pass the async q into the drawing function
     */
    this.showLogs = function() {
	var self= this;
	YUI().use('async-queue', function(Y) {
		var q = new Y.AsyncQueue(self.showLoadingScreen,
					 // The second callback will pause the Queue and send an XHR for data
					 function () {
					     q.pause();
					     var loggraph = new LogGraph.LogTable(q, self.getCleanCanvas(), self.starttime, self.endtime, self.filter);
					     self.currentRender = self.showLogs;
					 },
					 self.hideLoadingScreen);
		q.run();
	    }
	    );
    };

    this.serverGraph = function() {
	var self= this;
	YUI().use('async-queue', function(Y) {
		var q = new Y.AsyncQueue(self.showLoadingScreen,
					 // The second callback will pause the Queue and send an XHR for data
					 function () {
					     q.pause();
					     var servergraph = new LogGraph.ServerGraph(q, self.getCleanCanvas(), self.starttime, self.endtime, self.filter);
					     self.currentRender = self.showLogs;
					 },
					 self.hideLoadingScreen);
		q.run();
	    }
	    );
    };
    
    this.sessionGraph = function() {
	var self= this;
	YUI().use('async-queue', function(Y) {
		var q = new Y.AsyncQueue(self.showLoadingScreen,
					 // The second callback will pause the Queue and send an XHR for data
					 function () {
					     q.pause();
					     var sessiongraph = new LogGraph.SessionGraph(q, self.getCleanCanvas(), self.starttime, self.endtime, self.filter);
					     self.currentRender = self.sessionGraph;
					 },
					 self.hideLoadingScreen);
		q.run();
	    }
	    );
    };
    
    this.showStats = function() {
	var self= this;
	YUI().use('async-queue', function(Y) {
		var q = new Y.AsyncQueue(self.showLoadingScreen,
					 // The second callback will pause the Queue and send an XHR for data
					 function () {
					     q.pause();
					     var statgraph = new LogGraph.StatsGraph(q, self.getCleanCanvas(), self.starttime, self.endtime, self.filter);
					     self.currentRender = self.showStats;
					 },
					 self.hideLoadingScreen);
		q.run();
	    }
	    );
    };
};

LogGraph.error = function(description) {
    var errorPage = document.createElement("div");
    errorPage.className = "errorpage";
    var p = document.createElement("p");
    p.innerHTML = description;
    errorPage.appendChild(p);
    
    var span = document.createElement("span");
    p = document.createElement("p");
    span.className = "actionButton";
    span.innerHTML = "OK";
    span.onclick = function (evt) {
	document.body.removeChild(errorPage);
	delete errorPage;
    }
    p.appendChild(span);
    errorPage.appendChild(p);

    document.body.appendChild(errorPage);
};

LogGraph.ticker =function(allow_dups) {
    this.ticks = new Array();
    this.current_tick = 0;
    this.allow_dups = allow_dups;;
    
    this.tick = function(time) {
	if (time == this.ticks[this.ticks.length - 1] && this.allow_dups == true) 
	    return this.current_tick;
	
	this.ticks.push(time);
	return this.current_tick++;
    };
    
    this.current = function() {
	return this.current_tick;
    };
    
    this.reset = function() {
	while (this.ticks.length) {
	    this.ticks.pop();
	}
	this.current_tick = 0;
    };
};


LogGraph.timescale = function(starttime, endtime) {
    this.starttime = starttime;
    this.endtime = endtime;
    this.millis = endtime - starttime;
    
    this.draw = function(paper) {
	var scale = paper.set();
	scale.push(paper.path("M0 0 L" + paper.width + " 0"));
	
	for (var i = 0; i < paper.width; i += 100) {
	    scale.push(paper.path("M" + i + " 0 L" + i + " 5"));
	    //		var time = dateFormat((this.starttime + (i*ms_per_pixel)), "h:MM:ss,l");
		//	paper.text(i + 5, 10, time);
	}
	
	scale.attr({"stroke-width": 2});
    };
};

/*
  Fetch data from an uri and process it, the process data func returns true if any of the data is useful  
*/
LogGraph.loadData = function (asyncq, uri, processdata) {
    YUI().use("io-base", function(Y) {
	    function success(id, o, args) {
		var data = eval("(" + o.responseText + ")"); // Response data.
		if (data.error) {
		    LogGraph.error(data.error);
		} else {
		    if (!processdata(data)) {
			LogGraph.error("No data. Perhaps you should loosen your filter criteria.");
		    }
		}
		asyncq.run();
	    };
	    function failure(id, o, args) {
		LogGraph.error("Error contacting server: (" + o.status + ") " + o.statusText);
		asyncq.run();
	    };

	    Y.on('io:success', success, Y, []);
	    Y.on('io:failure', failure, Y, []);
	    
	    var request = Y.io(uri);
	});
}