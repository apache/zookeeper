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

// Opens a window to load files into the engine
LogGraph.fileSelector = function(callback) {
    var self = this;	
    this.callback = callback;
    this.selectedFiles = new Array();
    
    var divTag = document.createElement("div");
    divTag.id = "fileSelector" + Math.round(Math.random()*100000);
    //	divTag.className = "popUp";
    divTag.className = "selector fileSelector";
    document.body.appendChild(divTag);
    
    YUI().use('dd-drag', function(Y) {
	    //Selector of the node to make draggable
	    var dd = new Y.DD.Drag({
			node: '#' + divTag.id
		});   
	});
    
    var list = document.createElement("ul");
    divTag.appendChild(list);
    var selectedList = document.createElement("selectedlist");
    divTag.appendChild(selectedList);
    
    var clearanchor = document.createElement("span");
    clearanchor.innerHTML = "Remove All";
    clearanchor.className = "actionbutton";
    clearanchor.style.cssFloat = "right";
    clearanchor.onclick = function () {
	self.selectedFiles = new Array();
	self.updateSelectedList();
    };
    divTag.appendChild(clearanchor);
    
    var doneanchor = document.createElement("span");
    doneanchor.innerHTML = "Process Files";
    doneanchor.className = "actionbutton";
    doneanchor.style.cssFloat = "left";
    doneanchor.onclick = function () {
	self.callback(self.selectedFiles);
	document.body.removeChild(divTag);
	delete divTag;
    };
    divTag.appendChild(doneanchor);
    
    var cancelanchor = document.createElement("span");
    cancelanchor.innerHTML = "Cancel";
    cancelanchor.className = "actionbutton";
    cancelanchor.style.cssFloat = "left";
    cancelanchor.onclick = function () {
	document.body.removeChild(divTag);
	delete divTag;
    };
    divTag.appendChild(cancelanchor);
    
    this.createFileListItem = function (file) {
	var li = document.createElement("li");
	var a = document.createElement("a");
	if (file.type == "D") {
	    a.innerHTML = file.file + "/";
	    a.onclick = function () { self.updateList(file.path); };
	} else {
	    a.innerHTML = file.file;
	    a.onclick = function () { self.addSelectedFile(file.path); };
	}
	
	a.fullpath = file.path;;
	li.appendChild(a);
	return li;
    };
    
    this.addSelectedFile = function (file) {
	if (this.selectedFiles.indexOf(file) == -1) {
	    this.selectedFiles.push(file);
	    this.updateSelectedList();
	}
    };
    
    this.removeSelectedFile = function (file) {
	this.selectedFiles = this.selectedFiles.filter(function(f) { return !(file == f); });
	this.updateSelectedList();
    };
    
    this.createSelectedListItem = function (file) {
	var li = document.createElement("li");
	var a = document.createElement("a");
	li.className = "selectedFile";
	a.onclick = function () { self.removeSelectedFile(file); };
	a.innerHTML = file;
	li.appendChild(a);
	return li;
    };
    
    this.updateSelectedList = function () {
	while (selectedList.firstChild) { selectedList.removeChild(selectedList.firstChild); }
	
	for (var i in this.selectedFiles) {
	    var f = this.selectedFiles[i];
	    selectedList.appendChild(this.createSelectedListItem(f));
	}
    };
    
    this.updateList = function (base) {
	while (list.firstChild) list.removeChild(list.firstChild);
	
	// Create a YUI instance using io-base module.
	YUI().use("io-base", function(Y) {
		var uri = "/fs?path=" + base;
		
		// Define a function to handle the response data.
		function complete(id, o, args) {
		    var id = id; // Transaction ID.
		    var data = eval("(" + o.responseText + ")"); // Response data.
		    var parts = base.split("/").slice(0,-1);
		    var parent = ""
			if (parts.length < 2) {
			    parent = "/";
			} else {
			    parent = parts.join("/");
			}
		    if (base != "/") {
			var li = self.createFileListItem({"file": "..", type: "D", path: parent});
			list.appendChild(li);
		    }
		    for (var i in data) {
			var f = data[i];
			if (f.file[0] != '.') {
			    var li = self.createFileListItem(f);
			    list.appendChild(li);
			}
		    }
		};
		
		Y.on('io:complete', complete, Y, []);
		var request = Y.io(uri);
	    });
    };
    
    this.updateList("/");
};

// Open a window which loads files into the engine
LogGraph.fileLoader = function(files) {
    var div = document.createElement("div");
    div.id = "fileLoader";
    
    var imgArray = new Array();
    var pArray = new Array();
    for (var index in files) {
	var f = files[index];
	var p = document.createElement("p");
	var i = document.createElement("img");
	i.src = "load.gif";
	i.style.visibility = "hidden";
	imgArray.push(i);
	pArray.push(p);
	var span = document.createElement("span");
	span.innerHTML = f;
	
	p.appendChild(span);
	p.appendChild(i);
	
	div.appendChild(p);
    }
    
    var loadFile = function (index) {
	// Create a YUI instance using io-base module.
	YUI().use("io-base", function(Y) {
		var file = files[index];
		    var uri = "/loadfile?path=" + file;
		imgArray[index].style.visibility = "visible";
		
		// Define a function to handle the response data.
		function complete(id, o, args) {
		    var id = id; // Transaction ID.
			var data = eval("(" + o.responseText + ")"); // Response data.
			if (data.status == "ERR") {
			    var err = document.createElement("div");
			    err.innerHTML = data.error;
			    pArray[index].appendChild(err);
			} else if (data.status == "OK") {
			    var ok = document.createElement("div");
			    ok.innerHTML = "OK";
			    pArray[index].appendChild(ok);
			}
			
			imgArray[index].style.visibility = "hidden";
			if (index + 1 < files.length) {
			    loadFile(index + 1);
			} else {
			    //alert("DONE");
			}
		};
		
		Y.on('io:complete', complete, Y, []);
		var request = Y.io(uri);
	    });
    };
	
    var doneanchor = document.createElement("a");
    doneanchor.className = "actionbutton";
    doneanchor.innerHTML = "Done";
    doneanchor.onclick = function () {
	document.body.removeChild(div);
	delete div;
    };
    
    document.body.appendChild(div);
    if (files.length > 0) {
	loadFile(0);
    } else {
	div.innerHTML ="No files to load";
    }
    div.appendChild(doneanchor);
}

// select a time period
LogGraph.filterSelector = function(starttime, period, filter, callback) {
    var self = this;	
    this.callback = callback;
    
    // Container other widgets will be in
    var container = document.createElement("div");
    container.id = "filterSelector" + Math.round(Math.random()*100000);
    container.className = "selector filterSelector";
    document.body.appendChild(container);
    
    YUI().use('dd-drag', function(Y) {
	    //Selector of the node to make draggable
	    var dd = new Y.DD.Drag({
		    node: '#' + container.id
		});   
	});
    
    // Temporary loading screen
    var loadingp = document.createElement("p");
    loadingp.innerHTML = "Loading...";
    var loadimg = document.createElement("img");
    loadimg.src = "load.gif";
    loadingp.appendChild(loadimg);
    container.appendChild(loadingp);
    
    var addWithLabel = function (container, labeltxt, object) {
	var p = document.createElement("p");
	var label = document.createElement("label");
	label.innerHTML = labeltxt + ":";
	p.appendChild(label);
	p.appendChild(object);
	container.appendChild(p);
    };
    var draw = function(minstart, maxstart, entries) { 
	container.removeChild(loadingp);
	var inittime = minstart > starttime ? minstart : starttime;
	
	var numEntries = 0;
	var startspan = document.createElement("span");
	addWithLabel(container, "Start time", startspan);
	var startinput = document.createElement("input");
	startinput.type = "hidden";
	startinput.value = inittime;
	container.appendChild(startinput);
	var sliderspan = document.createElement("span");
	container.appendChild(sliderspan);
	
	var countspan = document.createElement("p");
	countspan.innerHTML = entries + " entries";;
	container.appendChild(countspan);
	
	var windowinput = document.createElement("input");
	windowinput.type = "text";
	windowinput.value = period;
	addWithLabel(container, "Time window (ms)", windowinput);
	
	var filterinput = document.createElement("textarea");
	filterinput.id = "filterinput";
	filterinput.value = filter;
	addWithLabel(container, "Filter", filterinput);

	/* done link, when clicked time is updated, */
	var doneanchor = document.createElement("a");
	doneanchor.className = "actionbutton";
	doneanchor.innerHTML = "Done";
	doneanchor.onclick = function () {
	    var start = parseInt(startinput.value);
	    var period = parseInt(windowinput.value);
	    var filter = filterinput.value;
	    document.body.removeChild(container);
	    delete container;
	    
	    update(start, period, filter, function() {
		    callback(start, period, filter, numEntries);
		});
	};
	container.appendChild(doneanchor);
	
	var update = function(start, period, filter, thenrun) {
	    startspan.innerHTML = dateFormat(start, "HH:MM:ss,l");
	    // get the min and max start time
	    YUI().use("io-base", function(Y) {
		    var uri = "/info?start=" + start + "&period=" + period + "&filter=" + filter;
		    function complete(id, o, args) {
			var data = eval("(" + o.responseText + ")"); 
			countspan.innerHTML = data.numEntries + " entries";
			numEntries = data.numEntries;
			if (thenrun) {
			    thenrun();
			}
		    };
		    
		    Y.on('io:complete', complete, Y, []);
		    var request = Y.io(uri);
		});
	};
	
	var updatewindow = function(evt) {		
	    var start = parseInt(startinput.value);
	    var period = parseInt(windowinput.value);
	    var filter = filterinput.value;
	    update(start, period, filter);
	};
	windowinput.onkeyup = updatewindow;

	
	YUI().use("slider", function (Y) {
		var input, slider; 
		
		function updateInput( e ) {
		    this.set( "value", e.newVal );
		    
		    update(parseInt(startinput.value), parseInt(windowinput.value), filterinput.value);
		}
		
		xSlider = new Y.Slider({min: minstart, max: maxstart, value: inittime, length: "1000px" });
		
		// Link the input value to the Slider
		xInput = Y.one( startinput );
		xInput.setData( { slider: xSlider } );
		
		// Pass the input as the 'this' object inside updateInput
		xSlider.after( "valueChange", updateInput, xInput );
		
		// Render the Slider next to the input
		xSlider.render(sliderspan);
	    });
	update(inittime, windowinput.value, filterinput);
    };
    
    // get the min and max start time
    YUI().use("io-base", function(Y) {
	    var uri = "/info";
	    function complete(id, o, args) {
		var data = eval("(" + o.responseText + ")"); 
		draw(data.startTime, data.endTime, data.numEntries);
	    };
	    
	    Y.on('io:complete', complete, Y, []);
	    var request = Y.io(uri);
	});
}