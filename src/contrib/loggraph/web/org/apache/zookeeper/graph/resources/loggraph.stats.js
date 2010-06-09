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

LogGraph.StatsGraph = function (asyncq, canvas, starttime, endtime, filter) {
    var processdata = function(data) {
	var r = Raphael(canvas);
	var x = data.map(function (x) { return x.time; });
	var y = data.map(function (x) { return x.count; });
	var xlabels = data.map(function (x) { return dateFormat(x.time, "HH:MM:ss,l"); } );
	var h1 = function () {
	    this.tags = r.set();
	    for (var i = 0, ii = this.y.length; i < ii; i++) {
		this.tags.push(r.g.tag(this.x, this.y[i], this.values[i], 160, 10).insertBefore(this).attr([{fill: "#fff"}, {fill: this.symbols[i].attr("fill")}]));
	    }
	};
	var h2 = function () {
	    this.tags && this.tags.remove();
	};
	r.g.linechart(40, 40, 1000, 500,  x, y, {shade: true, axis: "0 0 1 1", symbol: "x", southlabels: xlabels, axisxstep: xlabels.length - 1 , westAxisLabel: "Write requests", southAxisLabel: "Time (min)"}).hoverColumn(h1, h2);

	return true;
	//r.g.barchart(0, 0, 1000, 100,  y, {shade: true, symbol: "x"}).hoverColumn(h1, h2);
    };
    
    var uri = "/throughput?scale=minutes";
    LogGraph.loadData(asyncq, uri, processdata);    
};

    
