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

from django.conf.urls.defaults import patterns, url

urlpatterns = patterns('zkui',
  url(r'^$', 'views.index'),
  url(r'view/(?P<id>\d+)$', 'views.view'),
  url(r'clients/(?P<host>.+)$', 'views.clients'),
  url(r'tree/(?P<id>\d+)(?P<path>.+)$', 'views.tree'),
  url(r'create/(?P<id>\d+)(?P<path>.*)$', 'views.create'),
  url(r'delete/(?P<id>\d+)(?P<path>.*)$', 'views.delete'),
  url(r'edit/base64/(?P<id>\d+)(?P<path>.*)$', 'views.edit_as_base64'),
  url(r'edit/text/(?P<id>\d+)(?P<path>.*)$', 'views.edit_as_text')
)
