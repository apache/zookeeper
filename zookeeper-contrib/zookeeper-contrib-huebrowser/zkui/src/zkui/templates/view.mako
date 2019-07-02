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

${shared.header("ZooKeeper Browser > %s" % cluster['nice_name'])}

<%def name="show_stats(stats)">
    <thead>
      <tr><th>Key</th>
      <th width="100%">Value</th></tr>
    </thead>

    <tr><td>Version</td>
      <td>${stats.get('zk_version')}</td>
    </tr>

    <tr><td>Latency</td><td>
      Min: ${stats.get('zk_min_latency', '')}
      Avg: ${stats.get('zk_avg_latency', '')}
      Max: ${stats.get('zk_max_latency', '')}
    </td></tr>

    <tr><td>Packets</td>
      <td>Sent: ${stats.get('zk_packets_sent', '')}
      Received: ${stats.get('zk_packets_received', '')}
      </td>
    </tr>

    <tr><td>Outstanding Requests</td>
      <td>${stats.get('zk_outstanding_requests', '')}</td>
    </tr>

    <tr><td>Watch Count</td>
      <td>${stats.get('zk_watch_count', '')}</td>
    </tr>

    <tr><td>Open FD Count</td>
      <td>${stats.get('zk_open_file_descriptor_count', '')}</td>
    </tr>

    <tr><td>Max FD Count</td>
      <td>${stats.get('zk_max_file_descriptor_count', '')}</td>
    </tr>

</%def> 

<h2> ${cluster['nice_name']} Cluster Overview </h2>

${shared.info_button(url('zkui.views.tree', id=cluster['id'], path='/'), 'View Znode Hierarchy')}

<br /><br />

% if leader:
<h2>General</h2>

<table data-filters="HtmlTable">
  <thead>
    <tr><th>Key</th><th width="100%">Value</th></tr>
  </thead>

  <tr><td>ZNode Count</td>
    <td>${leader.get('zk_znode_count', '')}</td></tr>

  <tr><td>Ephemerals Count</td>
    <td>${leader.get('zk_ephemerals_count', '')}</td></tr>

  <tr><td>Approximate Data Size</td>
    <td>${leader.get('zk_approximate_data_size', '')} bytes</td></tr>

</table>
<br /><br />
% endif

% if leader:
  <h2>node :: ${leader['host']} :: leader</h2>

  ${shared.info_button(url('zkui.views.clients', host=leader['host']), 'View Client Connections')}

  <br /><br />
  <table data-filters="HtmlTable">
    ${show_stats(leader)}
    
    <tr><td>Followers</td>
      <td>${leader.get('zk_followers', '')}</td>
    </tr>

    <tr><td>Synced Followers</td>
      <td>${leader.get('zk_synced_followers', '')}</td>
    </tr>

    <tr><td>Pending Syncs</td>
      <td>${leader.get('zk_pending_syncs', '')}</td>
    </tr>
  
  </table>
<br /><br />
% endif

% for stats in followers:
  <h2>node :: ${stats['host']} :: follower</h2>
  <br />

  ${shared.info_button(url('zkui.views.clients', host=stats['host']), 'View Client Connections')}

  <br /><br />
  <table data-filters="HtmlTable">
    ${show_stats(stats)}
  </table>
  <br /><br />
% endfor

${shared.footer()}

