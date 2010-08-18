<%!
#  Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at

#     http://www.apache.org/licenses/LICENSE-2.0

# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
%>
<%namespace name="shared" file="shared_components.mako" />

${shared.header("ZooKeeper Browser > Edit Znode > %s" % path)}

<h2>Edit Znode Data :: ${path}</h2>
<hr /><br />

<form class="editZnodeForm" action="" method="POST">
<table align="center">
  ${form.as_table()|n}
<tr><td colspan="2" align="right">
  <button type="submit">Save</button>
</td></tr>
</table>
</form>

${shared.footer()}
