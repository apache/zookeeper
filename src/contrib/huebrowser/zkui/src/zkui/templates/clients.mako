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

${shared.header("ZooKeeper Browser > Clients > %s:%s" % (host, port))}

<h1>${host}:${port} :: client connections</h1>
<br />

% if clients:
  <table data-filters="HtmlTable"> 
  <thead>
    <tr>
      <th>Host</th>
      <th>Port</th>
      <th>Interest Ops</th>
      <th>Queued</th>
      <th>Received</th>
      <th>Sent</th>
  </thead>
  % for client in clients:
    <tr>
      <td>${client.host}</td>
      <td>${client.port}</td>
      <td>${client.interest_ops}</td>
      <td>${client.queued}</td>
      <td>${client.recved}</td>
      <td>${client.sent}</td>
    </tr>
  % endfor
  </table>
% endif

${shared.footer()}

