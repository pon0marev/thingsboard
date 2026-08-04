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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thingsboard.monitoring.config.transport.TransportInfo;
import org.thingsboard.monitoring.config.transport.TransportMonitoringTarget;
import org.thingsboard.monitoring.config.transport.TransportType;
import org.thingsboard.monitoring.data.MonitoredServiceKey;

import static org.assertj.core.api.Assertions.assertThat;

public class ProbeMetricsRecorderTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    public void setUp() {
        registry = new SimpleMeterRegistry();
    }

    private ProbeMetricsRecorder recorder(boolean enabled) {
        return new ProbeMetricsRecorder(registry, enabled, false, "acme.example.com",
                "https://acme.example.com", "wss://acme.example.com");
    }

    private TransportInfo transportInfo(TransportType type, String baseUrl) {
        return transportInfo(type, baseUrl, null);
    }

    private TransportInfo transportInfo(TransportType type, String baseUrl, String queue) {
        TransportMonitoringTarget target = new TransportMonitoringTarget();
        target.setBaseUrl(baseUrl);
        target.setQueue(queue);
        return new TransportInfo(type, target);
    }

    @Test
    public void whenDisabled_noMetersRegistered() {
        ProbeMetricsRecorder recorder = recorder(false);
        recorder.recordProbe(transportInfo(TransportType.MQTT, "tcp://acme.example.com:1883"), true);
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    public void mqttPlain_mapsToMqttProtocolAndConfiguredPort() {
        ProbeMetricsRecorder recorder = recorder(true);
        recorder.recordProbe(transportInfo(TransportType.MQTT, "tcp://acme.example.com:1883"), true);

        assertThat(registry.get("probe_success")
                .tags("domain", "acme.example.com", "check", "mqtt", "endpoint", "acme.example.com:1883", "kind", "probe")
                .gauge().value()).isEqualTo(1d);
    }

    @Test
    public void mqttTls_mapsToMqttsProtocol() {
        ProbeMetricsRecorder recorder = recorder(true);
        recorder.recordProbe(transportInfo(TransportType.MQTT, "ssl://acme.example.com:8883"), true);

        assertThat(registry.get("probe_success").tags("check", "mqtts").gauge().value()).isEqualTo(1d);
    }

    @Test
    public void coapPlain_mapsToCoapProtocol_defaultPortWhenMissing() {
        ProbeMetricsRecorder recorder = recorder(true);
        recorder.recordProbe(transportInfo(TransportType.COAP, "coap://acme.example.com"), true);

        assertThat(registry.get("probe_success")
                .tags("check", "coap", "endpoint", "acme.example.com:5683").gauge().value()).isEqualTo(1d);
    }

    @Test
    public void coapSecure_mapsToCoapsProtocol() {
        ProbeMetricsRecorder recorder = recorder(true);
        recorder.recordProbe(transportInfo(TransportType.COAP, "coaps://acme.example.com:5684"), true);

        assertThat(registry.get("probe_success").tags("check", "coaps").gauge().value()).isEqualTo(1d);
    }

    @Test
    public void http_mapsToHttpOrHttpsByScheme() {
        ProbeMetricsRecorder recorder = recorder(true);
        recorder.recordProbe(transportInfo(TransportType.HTTP, "http://acme.example.com"), true);
        recorder.recordProbe(transportInfo(TransportType.HTTP, "https://acme.example.com"), true);

        assertThat(registry.get("probe_success").tags("check", "http", "endpoint", "acme.example.com:80").gauge().value()).isEqualTo(1d);
        assertThat(registry.get("probe_success").tags("check", "https", "endpoint", "acme.example.com:443").gauge().value()).isEqualTo(1d);
    }

    @Test
    public void lwm2m_alwaysMapsToLwm2mRegardlessOfCoapScheme() {
        ProbeMetricsRecorder recorder = recorder(true);
        recorder.recordProbe(transportInfo(TransportType.LWM2M, "coap://acme.example.com:5685"), true);

        assertThat(registry.get("probe_success")
                .tags("check", "lwm2m", "endpoint", "acme.example.com:5685").gauge().value()).isEqualTo(1d);
    }

    @Test
    public void failedProbe_recordsZeroSuccess() {
        ProbeMetricsRecorder recorder = recorder(true);
        recorder.recordProbe(transportInfo(TransportType.MQTT, "tcp://acme.example.com:1883"), false);

        assertThat(registry.get("probe_success").tags("check", "mqtt").gauge().value()).isEqualTo(0d);
    }

    @Test
    public void login_mapsToLoginProtocolWithHostPortApiPathEndpointFromRestBaseUrl() {
        ProbeMetricsRecorder recorder = recorder(true);
        recorder.recordProbe(MonitoredServiceKey.LOGIN, true);

        assertThat(registry.get("probe_success")
                .tags("check", "login", "endpoint", "acme.example.com:443/api/auth/login").gauge().value()).isEqualTo(1d);
    }

    @Test
    public void ws_mapsToWsProtocolWithHostPortEndpointFromWsBaseUrl() {
        ProbeMetricsRecorder recorder = recorder(true);
        recorder.recordProbe(MonitoredServiceKey.WS, true);

        assertThat(registry.get("probe_success")
                .tags("check", "ws", "endpoint", "acme.example.com:443").gauge().value()).isEqualTo(1d);
    }

    @Test
    public void unrecognizedServiceKey_isIgnored() {
        ProbeMetricsRecorder recorder = recorder(true);
        recorder.recordProbe(MonitoredServiceKey.GENERAL, true);
        recorder.recordProbe(MonitoredServiceKey.EDQS, true);

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    public void secondCallWithSameLabels_updatesExistingGaugeInPlace() {
        ProbeMetricsRecorder recorder = recorder(true);
        recorder.recordProbe(transportInfo(TransportType.MQTT, "tcp://acme.example.com:1883"), true);
        recorder.recordProbe(transportInfo(TransportType.MQTT, "tcp://acme.example.com:1883"), false);

        assertThat(registry.get("probe_success").tags("check", "mqtt").gauge().value()).isEqualTo(0d);
        assertThat(registry.getMeters()).hasSize(1); // no duplicate meter registered
    }

    @Test
    public void recordActionDuration_addsStageTagToSeparateSeries() {
        ProbeMetricsRecorder recorder = recorder(true);
        TransportInfo target = transportInfo(TransportType.MQTT, "tcp://acme.example.com:1883");

        recorder.recordActionDuration(target, "request", 8);
        recorder.recordActionDuration(target, "ws_update", 700);

        assertThat(registry.get("probe_duration_ms")
                .tags("check", "mqtt", "action", "request").gauge().value()).isEqualTo(8d);
        assertThat(registry.get("probe_duration_ms")
                .tags("check", "mqtt", "action", "ws_update").gauge().value()).isEqualTo(700d);
        assertThat(registry.getMeters()).hasSize(2); // one series per stage, correctly distinct
    }

    @Test
    public void recordActionDuration_whenDisabled_isNoOp() {
        ProbeMetricsRecorder recorder = recorder(false);
        recorder.recordActionDuration(MonitoredServiceKey.LOGIN, "request", 8);
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    public void removeProbe_alsoRemovesStageDurationGauges() {
        ProbeMetricsRecorder recorder = recorder(true);
        TransportInfo target = transportInfo(TransportType.MQTT, "tcp://acme.example.com:1883");
        recorder.recordProbe(target, true);
        recorder.recordActionDuration(target, "request", 8);
        recorder.recordActionDuration(target, "ws_update", 700);
        assertThat(registry.getMeters()).hasSize(3); // success + 2 stages

        recorder.removeProbe(target);

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    public void removeProbe_thenRecordStageDurationAgain_reregistersCleanly() {
        // guards against the internal stagesByBaseTags bookkeeping leaking a stale entry that
        // would make removeProbe miss this series on a later, second removal
        ProbeMetricsRecorder recorder = recorder(true);
        TransportInfo target = transportInfo(TransportType.MQTT, "tcp://acme.example.com:1883");
        recorder.recordActionDuration(target, "request", 8);
        recorder.removeProbe(target);

        recorder.recordActionDuration(target, "request", 12);

        assertThat(registry.get("probe_duration_ms")
                .tags("check", "mqtt", "action", "request").gauge().value()).isEqualTo(12d);
        assertThat(registry.getMeters()).hasSize(1);

        recorder.removeProbe(target);
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    public void removeProbe_removesGaugeForRetiredTarget() {
        ProbeMetricsRecorder recorder = recorder(true);
        TransportInfo retired = transportInfo(TransportType.MQTT, "tcp://acme.example.com:1883");
        recorder.recordProbe(retired, true);
        assertThat(registry.getMeters()).hasSize(1);

        recorder.removeProbe(retired);

        assertThat(registry.find("probe_success").tags("check", "mqtt").gauge()).isNull();
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    public void removeProbe_thenRecordProbeAgain_reregistersGaugeCleanly() {
        ProbeMetricsRecorder recorder = recorder(true);
        TransportInfo target = transportInfo(TransportType.MQTT, "tcp://acme.example.com:1883");
        recorder.recordProbe(target, true);
        recorder.removeProbe(target);

        recorder.recordProbe(target, false);

        assertThat(registry.get("probe_success").tags("check", "mqtt").gauge().value()).isEqualTo(0d);
        assertThat(registry.getMeters()).hasSize(1); // no duplicate/orphaned meter left over from before removal
    }

    @Test
    public void removeProbe_whenDisabled_isNoOp() {
        ProbeMetricsRecorder recorder = recorder(false);
        recorder.removeProbe(MonitoredServiceKey.LOGIN);
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    public void wsConnect_and_wsSubscribe_alsoMapToWsProtocol() {
        ProbeMetricsRecorder recorder = recorder(true);
        recorder.recordProbe(MonitoredServiceKey.WS_CONNECT, true);
        recorder.recordProbe(MonitoredServiceKey.WS_SUBSCRIBE, false);

        // same series either way - WS_CONNECT recorded success, then WS_SUBSCRIBE overwrote it with failure
        assertThat(registry.get("probe_success").tags("check", "ws").gauge().value()).isEqualTo(0d);
    }

    @Test
    public void underscoreHostname_stillResolvesEndpoint() {
        // URI.getHost()/getPort() return null/-1 for authorities Java doesn't treat as valid hostnames -
        // e.g. underscores, a common docker-compose service-naming convention
        ProbeMetricsRecorder recorder = recorder(true);
        recorder.recordProbe(transportInfo(TransportType.MQTT, "tcp://tb_mqtt:1883"), true);

        assertThat(registry.get("probe_success")
                .tags("check", "mqtt", "endpoint", "tb_mqtt:1883").gauge().value()).isEqualTo(1d);
    }

    @Test
    public void underscoreHostname_noPort_fallsBackToDefaultPort() {
        ProbeMetricsRecorder recorder = recorder(true);
        recorder.recordProbe(transportInfo(TransportType.COAP, "coap://tb_coap"), true);

        assertThat(registry.get("probe_success")
                .tags("check", "coap", "endpoint", "tb_coap:5683").gauge().value()).isEqualTo(1d);
    }

    @Test
    public void removeProbe_withFreshButEqualTransportInfo_stillRemovesGauge() {
        // recordProbe/removeProbe are called with independently-constructed TransportInfo instances in
        // production (BaseHealthChecker's cached field vs. a fresh BaseHealthChecker.getInfo() call) -
        // only their type+baseUrl need to match, not object identity
        ProbeMetricsRecorder recorder = recorder(true);
        recorder.recordProbe(transportInfo(TransportType.MQTT, "tcp://acme.example.com:1883"), true);

        recorder.removeProbe(transportInfo(TransportType.MQTT, "tcp://acme.example.com:1883"));

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    public void distinctServiceKeysWithSameLabels_lastWriteWins() {
        // two targets sharing a host:port but differing only by queue (which isn't part of the label
        // taxonomy) collide onto the same series - documents the known, logged limitation rather than
        // silently losing one target's data without a trace
        ProbeMetricsRecorder recorder = recorder(true);
        TransportInfo first = transportInfo(TransportType.MQTT, "tcp://acme.example.com:1883", "QueueA");
        TransportInfo second = transportInfo(TransportType.MQTT, "tcp://acme.example.com:1883", "QueueB");
        assertThat(first).isNotEqualTo(second); // otherwise this test wouldn't actually exercise a collision

        recorder.recordProbe(first, true);
        recorder.recordProbe(second, false);

        assertThat(registry.get("probe_success").tags("check", "mqtt").gauge().value()).isEqualTo(0d);
        assertThat(registry.getMeters()).hasSize(1); // still one series, not two - the collision is real
    }

    @Test
    public void disabled_invalidWsBaseUrl_doesNotThrowAtConstruction() {
        ProbeMetricsRecorder recorder = new ProbeMetricsRecorder(registry, false, false, "acme.example.com",
                "https://acme.example.com", "not a valid uri");
        recorder.recordProbe(MonitoredServiceKey.WS, true);
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    public void enabled_invalidWsBaseUrl_doesNotThrowAtConstruction_wsProbeSkipped() {
        // otlpEnabled=true so this recorder is "enabled" and would normally resolve wsEndpoint eagerly
        ProbeMetricsRecorder recorder = new ProbeMetricsRecorder(registry, true, false, "acme.example.com",
                "https://acme.example.com", "not a valid uri");

        recorder.recordProbe(MonitoredServiceKey.WS, true);

        assertThat(registry.getMeters()).isEmpty(); // ws is skipped, but construction didn't throw
    }

    @Test
    public void enabled_invalidRestBaseUrl_doesNotThrowAtConstruction_loginProbeSkipped() {
        ProbeMetricsRecorder recorder = new ProbeMetricsRecorder(registry, true, false, "acme.example.com",
                "not a valid uri", "wss://acme.example.com");

        recorder.recordProbe(MonitoredServiceKey.LOGIN, true);

        assertThat(registry.getMeters()).isEmpty(); // login is skipped, but construction didn't throw
    }

}
