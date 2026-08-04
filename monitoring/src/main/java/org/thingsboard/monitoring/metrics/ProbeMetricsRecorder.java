/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.monitoring.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.monitoring.config.transport.TransportInfo;
import org.thingsboard.monitoring.config.transport.TransportType;
import org.thingsboard.monitoring.data.MonitoredServiceKey;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

@Component
@Slf4j
public class ProbeMetricsRecorder {

    public static final String PROBE_SUCCESS_METRIC = "probe_success";
    // per-action: tagged with "action" (e.g. "request"/"ws_update"/"connect"/"subscribe") in addition
    // to the base tags - there is no separate combined-total duration series
    public static final String PROBE_DURATION_METRIC = "probe_duration_ms";

    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final String domain;
    private final String loginEndpoint;
    private final String wsEndpoint;

    private final Map<GaugeKey, AtomicReference<Double>> gaugeValues = new ConcurrentHashMap<>();
    private final Map<TransportTagKey, Tags> transportTagsCache = new ConcurrentHashMap<>();
    // detects two distinct probes resolving to the same label set (e.g. two targets sharing a
    // host:port but different queue) - the label taxonomy has no room for a disambiguating tag,
    // so surface the collision instead of letting one silently overwrite the other's gauge
    private final Map<Tags, Object> tagsOwners = new ConcurrentHashMap<>();
    // logged once per colliding label set rather than every cycle - cleared alongside tagsOwners
    private final Set<Tags> warnedCollisions = ConcurrentHashMap.newKeySet();
    // which "action" values have been recorded under each probe's base tags, so removeProbe can find
    // and remove every action gauge for that probe without needing to know its actions up front
    private final Map<Tags, Set<String>> actionsByBaseTags = new ConcurrentHashMap<>();

    public ProbeMetricsRecorder(MeterRegistry meterRegistry,
                                 @Value("${monitoring.metrics.otlp.enabled:false}") boolean otlpEnabled,
                                 @Value("${monitoring.metrics.prometheus.enabled:false}") boolean prometheusEnabled,
                                 @Value("${monitoring.domain}") String domain,
                                 @Value("${monitoring.rest.base_url}") String restBaseUrl,
                                 @Value("${monitoring.ws.base_url}") String wsBaseUrl) {
        this.meterRegistry = meterRegistry;
        this.enabled = otlpEnabled || prometheusEnabled;
        this.domain = domain;
        this.loginEndpoint = this.enabled ? ProbeLabelResolver.tryResolveEndpoint("monitoring.rest.base_url", restBaseUrl, "login",
                endpoint -> endpoint + ProbeLabelResolver.LOGIN_PATH) : null;
        this.wsEndpoint = this.enabled ? ProbeLabelResolver.tryResolveEndpoint("monitoring.ws.base_url", wsBaseUrl, "ws",
                Function.identity()) : null;
    }

    public void recordProbe(Object serviceKey, boolean success) {
        withTags(serviceKey, "record", tags -> {
            warnIfLabelCollision(serviceKey, tags);
            setGauge(PROBE_SUCCESS_METRIC, tags, success ? 1d : 0d);
        });
    }

    // one series per sub-step of a probe (e.g. transport "request" vs "ws_update", ws "connect" vs
    // "subscribe") - same tags as recordProbe, plus an "action" tag
    public void recordActionDuration(Object serviceKey, String action, long durationMs) {
        withTags(serviceKey, "record action duration", tags -> {
            actionsByBaseTags.computeIfAbsent(tags, k -> ConcurrentHashMap.newKeySet()).add(action);
            setGauge(PROBE_DURATION_METRIC, tags.and("action", action), (double) durationMs);
        });
    }

    // for probes whose target is no longer being checked this cycle - either permanently (e.g. an
    // IP-based associate dropped from DNS) or transiently (e.g. login failed, so no transport check
    // ran) - without this, the gauge keeps exporting its last value regardless
    public void removeProbe(Object serviceKey) {
        withTags(serviceKey, "remove", tags -> {
            removeGauge(PROBE_SUCCESS_METRIC, tags);
            Set<String> actions = actionsByBaseTags.remove(tags);
            if (actions != null) {
                actions.forEach(action -> removeGauge(PROBE_DURATION_METRIC, tags.and("action", action)));
            }
            tagsOwners.remove(tags);
            warnedCollisions.remove(tags);
        });
        if (serviceKey instanceof TransportInfo transportInfo) {
            // otherwise this cache leaks the same way the gauges just did
            transportTagsCache.remove(TransportTagKey.of(transportInfo));
        }
    }

    private void warnIfLabelCollision(Object serviceKey, Tags tags) {
        Object previousOwner = tagsOwners.put(tags, serviceKey);
        if (previousOwner != null && !previousOwner.equals(serviceKey) && warnedCollisions.add(tags)) {
            log.warn("Probe metrics collision: [{}] and [{}] both resolve to labels {} - " +
                    "one will silently overwrite the other's gauge every cycle", previousOwner, serviceKey, tags);
        }
    }

    private void withTags(Object serviceKey, String verb, Consumer<Tags> body) {
        if (!enabled) {
            return;
        }
        try {
            Tags tags = resolveTags(serviceKey);
            if (tags == null) {
                return; // GENERAL, EDQS, or anything outside the documented label taxonomy
            }
            body.accept(tags);
        } catch (Exception e) {
            log.warn("Failed to {} probe metric for [{}]", verb, serviceKey, e);
        }
    }

    private Tags resolveTags(Object serviceKey) {
        if (serviceKey instanceof TransportInfo transportInfo) {
            return transportTags(transportInfo);
        } else if (loginEndpoint != null && MonitoredServiceKey.LOGIN.equals(serviceKey)) {
            return baseTags("login", loginEndpoint);
        } else if (wsEndpoint != null && (MonitoredServiceKey.WS.equals(serviceKey)
                || MonitoredServiceKey.WS_CONNECT.equals(serviceKey)
                || MonitoredServiceKey.WS_SUBSCRIBE.equals(serviceKey))) {
            return baseTags("ws", wsEndpoint);
        }
        return null;
    }

    private Tags transportTags(TransportInfo info) {
        // keyed on type+baseUrl only (not the whole TransportInfo, whose equals/hashCode transitively
        // reaches the target's mutable device/credentials) - the owning health checker calls this every
        // check cycle (as often as every 10s by default), so cache to skip re-parsing the URI each time
        return transportTagsCache.computeIfAbsent(TransportTagKey.of(info), key -> {
            ProbeLabelResolver.ProbeLabels labels = ProbeLabelResolver.resolveTransportLabels(key.type(), key.baseUrl());
            return baseTags(labels.check(), labels.endpoint());
        });
    }

    private Tags baseTags(String check, String endpoint) {
        return Tags.of("domain", domain, "check", check, "endpoint", endpoint, "kind", "probe");
    }

    private void setGauge(String metricName, Tags tags, double value) {
        gaugeValues.computeIfAbsent(new GaugeKey(metricName, tags), k -> {
            AtomicReference<Double> ref = new AtomicReference<>(value);
            Gauge.builder(metricName, ref, r -> r.get())
                    .tags(tags)
                    .register(meterRegistry);
            return ref;
        }).set(value);
    }

    private void removeGauge(String metricName, Tags tags) {
        meterRegistry.find(metricName).tags(tags).meters().forEach(meterRegistry::remove);
        gaugeValues.remove(new GaugeKey(metricName, tags));
    }

    private record GaugeKey(String metricName, Tags tags) {
    }

    private record TransportTagKey(TransportType type, String baseUrl) {
        static TransportTagKey of(TransportInfo info) {
            return new TransportTagKey(info.getType(), info.getTarget().getBaseUrl());
        }
    }

}