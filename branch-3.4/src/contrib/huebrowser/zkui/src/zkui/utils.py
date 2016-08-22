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

from zkui import settings

from django.http import Http404

def get_cluster_or_404(id):
  try:
    id = int(id)
    if not (0 <= id < len(settings.CLUSTERS)):
      raise ValueError, 'Undefined cluster id.'
  except (TypeError, ValueError):
    raise Http404()

  cluster = settings.CLUSTERS[id]
  cluster['id'] = id

  return cluster

