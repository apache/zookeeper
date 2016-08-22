// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/*
---

script: Zkui.js

description: Defines Zkui; a Hue application that extends CCS.JBrowser.

authors:
- Unknown

requires:
- ccs-shared/CCS.JBrowser

provides: [Zkui]

...
*/
ART.Sheet.define('window.art.browser.zkui', {
	'min-width': 620
});

var Zkui = new Class({

	Extends: CCS.JBrowser,

	options: {
		className: 'art browser logo_header zkui'
	},

	initialize: function(path, options){
		this.parent(path || '/zkui/', options);
	}

});
