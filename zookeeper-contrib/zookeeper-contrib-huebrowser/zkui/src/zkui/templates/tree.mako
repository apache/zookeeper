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

${shared.header("ZooKeeper Browser > Tree > %s > %s" % (cluster['nice_name'], path))}

<h1>${cluster['nice_name'].lower()} :: ${path}</h1>
<br />

<table data-filters="HtmlTable">
  <thead>
  <th colspan="2">Children</th>
  </thead>
  % for child in children:
    <tr><td width="100%">
      <a href="${url('zkui.views.tree', id=cluster['id'], \
          path=("%s/%s" % (path, child)).replace('//', '/'))}">
      ${child}</a>
    </td><td>
      <a title="Delete ${child}" class="delete frame_tip confirm_and_post" alt="Are you sure you want to delete ${child}?" href="${url('zkui.views.delete', id=cluster['id'], \
          path=("%s/%s" % (path, child)).replace('//', '/'))}">Delete</a>
    </td></tr>
  % endfor
</table>
<br />
<span style="float: right">
  ${shared.info_button(url('zkui.views.create', id=cluster['id'], path=path), 'Create New')}
</span>

<div style="clear: both"></div>

<h2>data :: base64 :: length :: ${znode.get('dataLength', 0)}</h2>
<br />

<textarea name="data64" style="width: 100%;" rows="5" readonly="readonly">${znode.get('data64', '')}</textarea>
<div style="clear: both"></div>
<span style="float: right">
  ${shared.info_button(url('zkui.views.edit_as_base64', id=cluster['id'], path=path), 'Edit as Base64')}
  ${shared.info_button(url('zkui.views.edit_as_text', id=cluster['id'], path=path), 'Edit as Text')}
</span>
<div style="clear: both"></div>
<br />

<h2>stat information</h2>
<br />

<table data-filters="HtmlTable">
  <thead><tr><th>Key</th>
    <th width="80%">Value</th></tr></thead>
  % for key in ('pzxid', 'ctime', 'aversion', 'mzxid', \
      'ephemeralOwner', 'version', 'mtime', 'cversion', 'czxid'):
    <tr><td>${key}</td><td>${znode[key]}</td></tr> 
  % endfor
</table>

<br />
<a target="_blank" rel="noopener noreferrer" href="http://zookeeper.apache.org/docs/current/zookeeperProgrammers.html#sc_zkStatStructure">Details on stat information.</a>

${shared.footer()}

