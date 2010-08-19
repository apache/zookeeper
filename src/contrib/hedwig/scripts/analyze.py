#!/usr/bin/env python

#  Licensed to the Apache Software Foundation (ASF) under one or more
#  contributor license agreements.  See the NOTICE file distributed with
#  this work for additional information regarding copyright ownership.
#  The ASF licenses this file to You under the Apache License, Version 2.0
#  (the "License"); you may not use this file except in compliance with
#  the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

from __future__ import with_statement
import sys, os, glob, re, collections, math, subprocess, unittest
from numpy import *
from commons import seqs, startup, strs, structs
import cairo
from pycha.bar import *

def sub():

  #
  # Parse/aggregate.
  #

  tputs = collections.defaultdict(list)
  lats = collections.defaultdict(list)
  for fname in glob.glob('sync-*-count-*-npar-*-rep-*.out'):
    m = re.match(r'sync-(\d+)-count-(\d+)-npar-(\d+)-rep-(\d+)\.out', fname)
    sync, count, npar, rep = map(int, m.groups())
    with file(fname) as f:
      m = re.search(r'finished subs, tput = ([\d\.]+) ops/s, avg latency = (\d+)', f.readlines()[-2])
      tput, lat = map(float, m.groups())
    tputs[sync, count, npar].append(tput)
    lats[sync, count, npar].append(lat)
  for d in tputs, lats:
    for k in d:
      d[k] = array(d[k]).mean(), array(d[k]).std()
      print k, d[k]

  #
  # Plot.
  #

  for title, ylabel, fname, d in [ ('Subscription throughput over three trials', 'Subscriptions per second', 'tput', tputs),
                                   ('Subscription latency over three trials', 'Round-trip time in ms', 'lat', lats) ]:
    means = dict((k, v[0]) for k,v in d.iteritems())
    sdevs = dict((k, v[1]) for k,v in d.iteritems())

    surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, 500, 400)
    syncs, counts, npars = [ sorted(set(x[i] for x in means))
                                for i in xrange(3) ]
    print syncs, counts, npars
    dataset = [ ( '%d topics, %s' %
                    ( count, 'synchronous' if sync else 'asynchronous' ),
                  [ ( npar/10, means[sync, count, npar], sdevs[sync, count, npar] )
                    for npar in npars ] )
                for count in counts
                for sync in syncs ]
    options = {'legend.position':
                {'top': None, 'left': None, 'bottom': 100, 'right': 20},
               'axis.x.ticks': [{'v': x, 'label': max(1,10*x)}
                                for i,(x,y,e) in enumerate(dataset[0][1])],
               'axis.x.label': 'Number of outstanding subscription requests',
               'axis.y.label': ylabel,
               'padding.left': 50,
               'title': title,
               'background.color': '#f0f0f0'}
    chart = VerticalBarChart(surface, structs.sparse_dict(options))
    chart.addDataset(dataset)
    chart.render()
    surface.write_to_png(fname + '.png')

def pub():

  def helper(do_pubs):

    def subhelper(keyname, pat, xlabel):

      #
      # Parse/aggregate.
      #

      print 'Analyzing', keyname, 'for', 'publishers' if do_pubs else 'receivers'
      print '========================'
      print

      tputs = collections.defaultdict(list)
      lats = collections.defaultdict(list)
      fnames = [ ( fname, tuple(map(int, m.groups())) )
                 for fname, m in filter( lambda m: m[1] is not None,
                                         ( ( fname, re.match(pat, fname) )
                                           for fname in os.listdir('.') ) ) ]
      tup2fname = dict( (tup, fname) for fname, tup in fnames )
      keys, reps, nodes = map(lambda xs: sorted(set(xs)),
                              zip(*(tup for fname, tup in fnames)))

      raw_table = []
      print '== raw data =='
      raw_table.append( [ keyname, 'rep', 'node', 'tput' ] + ( ['lat'] if do_pubs else [] ) + ['sum/mean tput', 'mean lat'] )
      for key in keys:
        for rep in reps:
          tmptputs = []
          tmplats = []
          for node in nodes:
            if (key, rep, node) in tup2fname:
              with file(tup2fname[key, rep, node]) as f:
                try:
                  if do_pubs:
                    m = re.search(r'finished acked pubs, tput = ([\d\.]+) ops/s, avg latency = (\d+)', f.readlines()[-2])
                    tput, lat = map(float, m.groups())
                  else:
                    m = re.search(r'finished recvs, tput = ([\d\.]+) ops/s', f.read())
                    [tput] = map(float, m.groups())
                except AttributeError:
                  print >> sys.stderr, "While processing", tup2fname[key, rep, node]
                  raise
              raw_table.append( [ key, rep, node, tput ] + ( [lat] if do_pubs else [] ) + ['',''] )
              tmptputs.append(tput)
              if do_pubs: tmplats.append(lat)
          if keyname == 'npubs': tputs[key].append(sum(tmptputs))
          else: tputs[key].append(array(tmptputs).mean())
          if do_pubs: lats[key].append(array(tmplats).mean())
          if len(nodes) > 1:
            raw_table.append( [''] * (len(raw_table[0]) - 2) + [tputs[key][-1]] + ( [lats[key][-1]] if do_pubs else [] ) )
      print strs.show_table_by_rows(raw_table)
      print

      print '== aggregated over reps =='
      agg_table = []
      agg_table.append( ( keyname, 'mean', 'sd' ) )
      for d in tputs, lats:
        for k in d:
          d[k] = array(d[k]).mean(), array(d[k]).std()
          agg_table.append( ( k, d[k][0], d[k][1] ) )
      print strs.show_table_by_rows(agg_table)
      print

      #
      # Plot.
      #

      if do_pubs:
        plots = [ ('Publishing throughput over three trials', 'Publishes per second', '%s-pub-tput' % keyname, tputs),
                  ('Publishing latency over three trials', 'Round-trip time in ms', '%s-pub-lat' % keyname, lats) ]
      else:
        plots = [ ('Receiving throughput over three trials', 'Receives per second', '%s-recv-tput' % keyname, tputs) ]
      for title, ylabel, fname, d in plots:
        means = dict((k, v[0]) for k,v in d.iteritems())
        sdevs = dict((k, v[1]) for k,v in d.iteritems())

        surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, 500, 400)
        dataset = [ ( 'main',
                      [ ( i, means[key], sdevs[key] )
                        for i, key in enumerate(keys) ] ) ]
        options = {'legend.position':
                    {'top': None, 'left': None, 'bottom': 100, 'right': 20},
                   'axis.x.ticks': [{'v': x, 'label': k}
                                    for k,(x,y,e) in zip(keys, dataset[0][1])],
                   'axis.x.label': xlabel,
                   'axis.y.label': ylabel,
                   'padding.left': 50,
                   'title': title,
                   'background.color': '#f0f0f0'}
        chart = VerticalBarChart(surface, structs.sparse_dict(options))
        chart.addDataset(dataset)
        chart.render()
        surface.write_to_png(fname + '.png')

      print
      print
      print

    nodetype = 'pub' if do_pubs else 'recv'
    mode_npar = seqs.mode(
        int(m.group(1)) for m in
        [re.search('npar-(\d+)', fname) for fname in os.listdir('.')]
        if m is not None )
    subhelper('nrecvs', 'nrecvs-(\d+)-npubs-1-npar-%s-rep-(\d+)-%s-(\d+)' % (mode_npar, nodetype),
              'Number of receivers')
    subhelper('npubs', 'nrecvs-1-npubs-(\d+)-npar-%s-rep-(\d+)-%s-(\d+)' % (mode_npar, nodetype),
              'Number of publishers')
    subhelper('npar', 'nrecvs-1-npubs-1-npar-(\d+)-rep-(\d+)-%s-(0)' % nodetype,
              'Number of outstanding publish requests')

  helper(True)
  helper(False)

def main(argv):
  if argv[1] == 'sub': sub()
  elif argv[1] == 'pub': pub()
  else: return unittest.main()

startup.run_main()

# vim: et sw=2 ts=2
