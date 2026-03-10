/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.metrics.prometheus;

import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import io.prometheus.metrics.model.snapshots.Quantile;
import io.prometheus.metrics.model.snapshots.SummarySnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the logic of converting a PrometheusRegistry scrape result into a sequence of key-value pairs.
 */
public class PrometheusRegistryDumper {

    private final PrometheusRegistry registry;

    public PrometheusRegistryDumper(PrometheusRegistry registry) {
        this.registry = registry;
    }

    /**
     * Dumps all metrics from the PrometheusRegistry into a key-value map.
     *
     * @return a map containing all the metrics
     */
    public Map<String, Object> dump() {
        Map<String, Object> allMetrics = new LinkedHashMap<>();
        MetricSnapshots metricSnapshots = registry.scrape();
        for (MetricSnapshot snapshot : metricSnapshots) {
            Map<String, Object> convertedMetrics = null;
            if (snapshot instanceof CounterSnapshot) {
                convertedMetrics = convert((CounterSnapshot) snapshot);
            } else if (snapshot instanceof GaugeSnapshot) {
                convertedMetrics = convert((GaugeSnapshot) snapshot);
            } else if (snapshot instanceof SummarySnapshot) {
                convertedMetrics = convert((SummarySnapshot) snapshot);
            }

            if (convertedMetrics != null) {
                allMetrics.putAll(convertedMetrics);
            }
        }
        return allMetrics;
    }

    private Map<String, Object> convert(CounterSnapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        String metricName = snapshot.getMetadata().getName();
        for (CounterSnapshot.CounterDataPointSnapshot dataPoint : snapshot.getDataPoints()) {
            result.put(buildKeyForDump(metricName, dataPoint.getLabels()), dataPoint.getValue());
        }
        return result;
    }

    private Map<String, Object> convert(GaugeSnapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        String metricName = snapshot.getMetadata().getName();
        for (GaugeSnapshot.GaugeDataPointSnapshot dataPoint : snapshot.getDataPoints()) {
            result.put(buildKeyForDump(metricName, dataPoint.getLabels()), dataPoint.getValue());
        }
        return result;
    }

    private Map<String, Object> convert(SummarySnapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        String metricName = snapshot.getMetadata().getName();
        for (SummarySnapshot.SummaryDataPointSnapshot dataPoint : snapshot.getDataPoints()) {
            double count = dataPoint.getCount();
            double sum = dataPoint.getSum();
            double avg = (count == 0) ? 0 : sum / count;

            // Add metrics in the requested order with prefixes
            result.put(buildKeyForDump(metricName + "_avg", dataPoint.getLabels()), avg);
            // Note: Prometheus Summary does not provide min/max, so they are omitted.
            result.put(buildKeyForDump(metricName + "_count", dataPoint.getLabels()), count);
            result.put(buildKeyForDump(metricName + "_sum", dataPoint.getLabels()), sum);

            // A summary is considered "advanced" if it has more than one quantile configured.
            boolean isAdvanced = dataPoint.getQuantiles().size() > 1;

            if (isAdvanced) {
                List<Quantile> quantiles = new ArrayList<>();
                dataPoint.getQuantiles().forEach(quantiles::add);
                quantiles.sort(Comparator.comparingDouble(Quantile::getQuantile));

                for (Quantile quantile : quantiles) {
                    String quantileValue = String.valueOf(quantile.getQuantile());
                    switch (quantileValue) {
                    default:
                        break;
                    case "0.5":
                        result.put(buildKeyForDump(metricName, dataPoint.getLabels().add("quantile", quantileValue)),
                                quantile.getValue());
                        break;
                    case "0.95":
                        result.put(buildKeyForDump(metricName, dataPoint.getLabels().add("quantile", quantileValue)),
                                quantile.getValue());
                        break;
                    case "0.99":
                        result.put(buildKeyForDump(metricName, dataPoint.getLabels().add("quantile", quantileValue)),
                                quantile.getValue());
                        break;
                    }
                }
            }
        }
        return result;
    }

    /**
     * Builds a string key for a given metric and its labels, in a format suitable for the dump output.
     *
     * @param metricName
     *            the name of the metric
     * @param labels
     *            the labels associated with the metric
     *
     * @return a formatted string key
     */
    private String buildKeyForDump(String metricName, Labels labels) {
        StringBuilder sb = new StringBuilder();
        sb.append(metricName);
        if (labels.size() > 0) {
            sb.append("{");
            for (int i = 0; i < labels.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(labels.getName(i)).append("=\"").append(labels.getValue(i)).append("\"");
            }
            sb.append("}");
        }
        return sb.toString();
    }
}
