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

LogGraph.ServerGraph = function(asyncq, canvas, starttime, endtime, filter) {
    this.starttime = starttime;
    this.endtime = endtime;
    this.millis = endtime - starttime;
    this.nextserverid = 0;
    this.serveroffset = 100;
    this.filter = filter;
    
    this.pixels_per_tick =  20;
    this.ticker = new LogGraph.ticker();

    
    var paper = Raphael(canvas, 1, 1);

    var self = this;

    this.timescale = new LogGraph.timescale(starttime, endtime);
    this.objects = new Array();
    
    this.add = function(obj) {
	this.objects.push(obj);
    }
    
    this.tick_to_x = function (timestamp) {
	var x = timestamp * this.pixels_per_tick; 
	return x;
    }; 
    
    this._drawTime  = function(paper, x, time) {
	var p = paper.path("M" + x + " 0 L" + x + " " + paper.height);
	var t = paper.text(x, 10, dateFormat(time, "h:MM:ss,l"));
	
	t.hide();
	p.mouseover(function(evt) {
		t.show();
		p.attr({stroke: "red"});
	    });
	p.mouseout(function(evt) {
		t.hide();
		p.attr({stroke: "lightgray"});
	    });
	
	return p;
    };
    
    this.draw = function(paper) {
	var grid = paper.set();
	for (var i = 0; i < paper.height; i += 20) {
	    grid.push(paper.path("M0 " + i + " L" + paper.width + " " + i));
	}
	var lasttick = this.starttime;
	var scale = 500; // 500 ms
	
	var y = 0;
	
	for (var t = 0, len = this.ticker.ticks.length; t < len; t++) {
	    var basex = t * this.pixels_per_tick;
	    var thistick = this.ticker.ticks[t];
	    var nexttick = t + 1 == this.ticker.ticks.length ? this.endtime : this.ticker.ticks[t+1];
	    if (nexttick == thistick) {
		continue;
	    }
	    var time = thistick - lasttick;
	    var first = scale - (lasttick % scale);
	    
	    /*		for (var i = 0; (first+scale*i) < time; i++) {
			
			var toffset = first+scale*i;
			var x = basex + LogGraph._pixels_per_tick * toffset/time;
			grid.push(this._drawTime(paper, x, lasttick + toffset, grid));
			
			}*/
	    
	    
	    //grid.push(paper.path("M" + i + " 0 L" + i + " " + paper.height));
	    lasttick = thistick;
	}
	grid.attr({stroke: "lightgray"});
	this.timescale.draw(paper);
	
	for (o in this.objects) {
	    this.objects[o].draw(paper);
	}
    };


    var processdata = function(data) {
	var servermap = {};
	var servers = data.servers;
	var count = 0;
	for (s in servers) {
	    var server = new LogGraph.ServerGraph.server(self, "Server " + servers[s]);
	    servermap[servers[s]] = server;
	    self.add(server);
	    count++;
	}
	
	var messages = {};
	var events = data.events;
	for (var i in events) {
	    var e = events[i];
	    var t = e.time;
	    if (e.type == "stateChange") {
		servermap[e.server].addState(e.state, self.ticker.tick(e.time));
	    }
	    if (e.type == "postmessage") {
		src = servermap[e.src];
		dst = servermap[e.dst];
		var key = "key:s" + e.src + ",d" + e.dst + ",z" + e.zxid;
		    
		var m = new LogGraph.ServerGraph.message(self, src, self.ticker.tick(e.time), dst, e.zxid);
		messages[key] = m;
	    } 
	    if (e.type == "delivermessage") {
		var key = "key:s" + e.src + ",d" + e.dst + ",z" + e.zxid;
		
		var m = messages[key];
		if (m) {
		    m.dsttime = self.ticker.tick(e.time);
		    m.name = "Propose";
		    self.add(m);
		    delete messages[key];
		}
	    } 
	    if (e.type == "exception") {
		servermap[e.server].addException(self.ticker.tick(e.time), e.text, e.time);
	    }
	    count++;
	}
	
	for (var i in messages) {
	    var m = messages[i];
	    m.markIncomplete();
	    self.add(m);
	    count++;
	}

	if (count != 0) {
	    paper.setSize(self.tick_to_x(self.ticker.current()), 1000);	    
	    
	    var line = paper.path("M0 0 L0 1000");	    
	    line.attr({"stroke": "red", "stroke-dasharray": "- "});
	    var base = canvas.offsetLeft;// + ((canvas.offsetWidth - paper.width)/2);
	    canvas.onmousemove = function (evt) {
		var x = evt.screenX - base;
		
		line.attr({"path": "M" + x + " 0 L"+ x +" 1000"});
		
	    };

	    self.draw(paper);
	    return true;
	} else {
	    return false;
	}
    };
		
    var uri = "/data?start=" + self.starttime + "&end=" + self.endtime + "&filter=" + filter;

    LogGraph.loadData(asyncq, uri, processdata);    
};

LogGraph.ServerGraph.server = function (graph, name) {
    this.graph = graph;
    this.serverid = graph.nextserverid++;
    this.name = name;
    this.y = (this.serverid * 300 + graph.serveroffset);
    this.states = new Array();
    this.exception = new Array();
    
    this.addState = function(state, time) {
	this.states.push([state, time]);
    }
    
    this.addException = function(tick, exception, time) {
	this.exception.push(new LogGraph.ServerGraph.exception(this.graph, tick, exception, time));
    }
    
    this.draw = function(paper) {
	var st = paper.set();
	st.push(paper.path("M0 " + this.y + " L" + paper.width + " " + this.y));
	st.push(paper.text(20, this.y - 10, this.name));
	st.attr({stroke: "gray"});
	
	var numstates = this.states.length;
	
	for (s = 0; s < numstates; s++) {
	    var style = {};
	    switch (this.states[s][0]) {
	    case "INIT": style = {stroke: "yellow", "stroke-width":3}; break;
	    case "FOLLOWING": style = {stroke: "lightgreen", "stroke-width":7}; break;
	    case "LEADING": style = {stroke: "green", "stroke-width":10}; break;
	    case "LOOKING": style = {stroke: "orange", "stroke-width":5}; break;
	    }
	    var startx = this.graph.tick_to_x(this.states[s][1]);
	    var endx = s + 1 < numstates ? this.graph.tick_to_x(this.states[(s+1)][1]) : paper.width;
	    var p = paper.path("M" + startx + " " + this.y + " L" + endx + " " + this.y);
	    p.attr(style);
	}
	
	for (e in this.exception) {
	    this.exception[e].draw(paper, this);
	}
    }
};    
    
LogGraph.ServerGraph.message = function(graph, src, srctime, dst, zxid) {
    this.graph = graph;
    this.src = src;
    this.srctime = srctime;
    this.dst = dst;
    this.dsttime = 0; //dsttime;
    this.name = "Unknown";
    this.zxid = zxid;
    this.moreinfo = "No extra information";
    this.incomplete = false;
    
    this.markIncomplete = function() {
	this.incomplete = true;
	this.dsttime = this.srctime;
    }

    this.draw = function(paper) {
	var srcx = this.graph.tick_to_x(this.srctime);
	var dstx = this.graph.tick_to_x(this.dsttime);
	
	var arrow = paper.set();
	var p = paper.path("M" + srcx + " " + this.src.y + " L" + dstx + " " + this.dst.y);
	arrow.push(p);
	
	var tx = (srcx + dstx)/2;
	var ty = (this.src.y + this.dst.y)/2;
	var t = paper.text(tx, ty, this.name);
	
	var gradiant = (this.dst.y - this.src.y)/(dstx - srcx);
	var angle = Math.atan(gradiant) * 57.2958;
	t.rotate(angle, true);
	
	var arrowl = paper.path("M" + dstx + " " + this.dst.y + " L" + (dstx - 10) +" " + this.dst.y);
	arrowl.rotate(angle + 20, dstx, this.dst.y);
	arrow.push(arrowl);
	var arrowr = paper.path("M" + dstx + " " + this.dst.y + " L" + (dstx - 10) +" " + this.dst.y);
	arrowr.rotate(angle - 20, dstx, this.dst.y);
	arrow.push(arrowr);
	
	arrow.attr({"stroke-width": 2, stroke: "gray"});
	if (this.incomplete) {
	    arrow.attr({"stroke-dasharray": "- .", stroke: "pink", "stroke-width": 2});
	}
	arrow.mouseover(function(evt) {
		t.attr({"font-size": 20});
		arrow.attr({stroke: "red", "stroke-width": 3});
	    });
	arrow.mouseout(function(evt) {
		t.attr({"font-size": 10});
		
		if (this.incomplete) {
		    arrow.attr({stroke: "pink", "stroke-width": 2});
		} else {
		    arrow.attr({stroke: "gray", "stroke-width": 2});
		}
	    });
	
	

	arrow.click(function(evt) { 
		var popup = document.createElement("div");
		popup.className = "popUp";
		popup.innerHTML = "zxid: " + parseInt(this.zxid).toString(16);

		popup.style.top = evt.clientY;
		popup.style.left = evt.clientX;
		document.body.appendChild(popup);

		popup.onclick = function(evt) { 
		    document.body.removeChild(popup);
		};
	    });
    }
};
    
LogGraph.ServerGraph.exception = function(graph, tick, exceptiontext, time) {
    this.graph = graph;
    this.time = time;
    this.text = exceptiontext;
    this.tick = tick;
    
    var self = this;

    this.draw = function(paper, server) {
	var center = this.graph.tick_to_x(this.tick);
	var p = paper.circle(center, server.y, 5);
	p.attr({stroke: "orange", fill: "red"});
	
	p.mouseover(function(evt) {
		p.popup = document.createElement("div");
		p.popup.className = "popUp";
		p.popup.innerHTML = self.text.replace("\n", "<br/>");;
		p.popup.style.top = server.y + 50;
		p.popup.style.left = center + 25;
		document.body.appendChild(p.popup);

		p.animate({r: 10}, 500, "elastic");
	    });
	p.mouseout(function(evt) {
		document.body.removeChild(p.popup);
		p.animate({r: 5}, 100);
	    });
    }
};
    
