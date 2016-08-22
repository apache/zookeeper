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

LogGraph.SessionGraph = function (asyncq, canvas, starttime, endtime, filter) {
    this.sessions = new Array();
    this.counter = 0;
    this.exceptions = new Array();
    
    this.pix_per_ticks = 4;
    this.pix_per_session = 7;

    var paper = Raphael(canvas, 1, 1);
    this.ticker = new LogGraph.ticker();
    var self = this;

    this.starttime = starttime;
    this.endtime = endtime;
    this.filter = filter;

    this.findOrCreateSession = function(id) {
	if (this.sessions[id] == undefined) {
	    this.sessions[id] = new LogGraph.SessionGraph.session(this, ++this.counter, id);
	}
	return this.sessions[id];
    }
    
    this.height = function () { return this.counter * this.pix_per_session + 10; };
    this.width = function () { return (self.ticker.current() * this.pix_per_ticks); };

    this.draw = function(paper) {
	

	var line = paper.path("M0 0 L0 " + this.height());	    
	line.attr({"stroke": "red", "stroke-dasharray": "- "});
	var base = canvas.offsetLeft;
	var width = this.width();
	canvas.onmousemove = function (evt) {
	    var x = evt.clientX - base;

	    line.attr({"path": "M" + x + " 0 L" + x + " " + self.height() });
	};	
	
	for (var i in this.sessions) {
	    var s = this.sessions[i];
	    s.draw(paper);
	}
    };

    var processdata = function(data) {
	var count = 0;
	for (var i in data.events) {
	    var e = data.events[i];
	    if (e.type == "transaction") {
		e.tick = self.ticker.tick(e.time, true);
		var session = self.findOrCreateSession(e.client);
		session.addEvent(e);
		count++;
	    }
	}
	paper.setSize(self.width(), self.height());
	
	if (count != 0) {
	    self.draw(paper);
	    return true;
	} else {
	    return false;
	}
    };
    
    var uri = "/data?start=" + self.starttime + "&end=" + self.endtime + "&filter=" + filter;

    LogGraph.loadData(asyncq, uri, processdata);    
};

LogGraph.SessionGraph.sessionevent = function () {
    this.time = time;
    this.type = type;
    this.client = client;
    this.cxid = cxid;
    this.zxid = zxid;
    this.op = op;
    this.extra = extra;
};

LogGraph.SessionGraph.sessionEventPopup = function (obj, e, x, y) {
    obj.click(function(evt) {
	    var popup = document.createElement("div");
	    popup.className = "popUp";

	    var closebutton = document.createElement("div");
	    closebutton.className = "closebutton";
	    closebutton.title = "Close popup";
	    closebutton.innerHTML = "&times;";
	    popup.appendChild(closebutton);
	    closebutton.onclick= function(evt) { popup.style.visibility = "hidden"; document.body.removeChild(popup) };
	    var txt = document.createElement("span");
	    txt.innerHTML = "session: " + e.client + "<br/>op: " + e.op + "<br/>zxid: " + e.zxid + "<br/>time: " + e.time + "<br/>extra: " + e.extra;
	    popup.appendChild(txt);
	    
	    popup.style.top = y;
	    popup.style.left = x;
	    document.body.appendChild(popup);
	    
	    YUI().use('dd-drag', function(Y) {
		    //Selector of the node to make draggable
		    var dd = new Y.DD.Drag({
			    node: popup
			});   
		});
	});
};
    
LogGraph.SessionGraph.session = function (graph, index, id) {
    this.index = index;
    this.id = id;
    this.graph = graph;
    
    this.events = new Array();
    this.starttick = 0;
    this.endtick = undefined;
    
    this.addEvent = function(e) {
	this.events.push(e);
	
	if (e.op == "createSession") {
	    //		document.write("createSession for " + id.toString(16));
	    this.starttick = e.tick;
	} else if (e.op == "closeSession") {
	    this.endtick = e.tick;
	}
    },
    
    this._attach_action = function (sess, label) {
	sess.mouseover(function(evt) {
		label.show();
		sess.attr({stroke: "gray"});
	    });
	
	sess.mouseout(function(evt) {
		label.hide();
		sess.attr({stroke: "black"});
	    });
    },
    
    this.drawEvent = function (paper, y, e) {
	var x = e.tick * this.graph.pix_per_ticks;;
	var s = paper.path("M" + x + " " + (y - 3) + " L" + x + " " + (y + 3));
	s.attr({"stroke-width": 2});
	if (e.op == "error") {
	    s.attr({"stroke": "red"});
	} 
	s.mouseover(function(evt) {
		s.attr({"stroke-width": 5});
	    });
	
	s.mouseout(function(evt) {
		s.attr({"stroke-width": 2});
	    });
	
	LogGraph.SessionGraph.sessionEventPopup(s, e, x, y);
    },
    
    this.draw = function(paper) {
	var y = this.index*this.graph.pix_per_session;;
	var start = this.starttick * this.graph.pix_per_ticks;
	var end = this.endtick * this.graph.pix_per_ticks;
	
	var sess = paper.set();
	
	if (this.endtick == undefined) {
	    end = this.graph.width();
	} 

	sess.push(paper.path("M" + start + " " + y + " L" + end + " " + y));
	for (var i in this.events) {
	    var e = this.events[i];
	    this.drawEvent(paper, y, e);
	}
	
	//sess.attr({"stroke-width": 3});
	label = paper.text(start + 100, y, this.id);
	label.attr({"font-size": "14px"});
	label.hide();
	this._attach_action(sess, label);
    }
};

