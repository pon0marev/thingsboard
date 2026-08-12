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
package org.thingsboard.monitoring.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.monitoring.client.TbClient;
import org.thingsboard.monitoring.client.WsClient;
import org.thingsboard.monitoring.client.WsClientFactory;
import org.thingsboard.monitoring.config.transport.TransportMonitoringConfig;
import org.thingsboard.monitoring.config.transport.TransportMonitoringTarget;
import org.thingsboard.monitoring.config.transport.TransportType;
import org.thingsboard.monitoring.data.MonitoredServiceKey;
import org.thingsboard.monitoring.metrics.ProbeMetricsRecorder;
import org.thingsboard.monitoring.util.TbStopWatch;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BaseMonitoringServiceProbeMetricsTest {

    private TbClient tbClient;
    private WsClientFactory wsClientFactory;
    private MonitoringReporter reporter;
    private ProbeMetricsRecorder probeMetricsRecorder;
    private TestMonitoringService service;
    private WsClient wsClient;
    private BaseHealthChecker<TransportMonitoringConfig, TransportMonitoringTarget> healthChecker;

    @BeforeEach
    public void setUp() throws Exception {
        tbClient = mock(TbClient.class);
        wsClientFactory = mock(WsClientFactory.class);
        reporter = mock(MonitoringReporter.class);
        probeMetricsRecorder = mock(ProbeMetricsRecorder.class);
        // enabled by default so the existing checkAccepted()-fires assertions below keep exercising
        // the fallback path itself; the disabled-guard behavior gets its own test that overrides this
        when(probeMetricsRecorder.isEnabled()).thenReturn(true);
        wsClient = mock(WsClient.class);
        when(wsClient.subscribeForTelemetry(any(), any())).thenReturn(wsClient);

        service = new TestMonitoringService();
        ReflectionTestUtils.setField(service, "tbClient", tbClient);
        ReflectionTestUtils.setField(service, "wsClientFactory", wsClientFactory);
        ReflectionTestUtils.setField(service, "reporter", reporter);
        ReflectionTestUtils.setField(service, "probeMetricsRecorder", probeMetricsRecorder);
        ReflectionTestUtils.setField(service, "stopWatch", new TbStopWatch());

        // one stub health checker so runChecks() doesn't short-circuit on the "healthCheckers.isEmpty()" guard;
        // its own check() outcome is irrelevant to this test (it's exercised in BaseHealthCheckerProbeMetricsTest).
        // getTarget() must be stubbed: BaseMonitoringService.check() reads target.isCheckDomainIps() right
        // after invoking it, and an unstubbed null there would NPE out of a "successful" run.
        healthChecker = mock(BaseHealthChecker.class);
        TransportMonitoringTarget target = new TransportMonitoringTarget();
        target.setCheckDomainIps(false);
        when(healthChecker.getTarget()).thenReturn(target);
        List<BaseHealthChecker<TransportMonitoringConfig, TransportMonitoringTarget>> healthCheckers =
                (List) ReflectionTestUtils.getField(service, "healthCheckers");
        healthCheckers.add(healthChecker);
    }

    @Test
    public void successfulLoginAndWs_recordsBothAsSuccessful() throws Exception {
        when(tbClient.logIn()).thenReturn("token");
        when(wsClientFactory.createClient("token")).thenReturn(wsClient);
        when(wsClient.waitForReply()).thenReturn(null);

        service.runChecks();

        verify(probeMetricsRecorder).recordProbe(eq(MonitoredServiceKey.LOGIN), eq(true));
        verify(probeMetricsRecorder).recordProbe(eq(MonitoredServiceKey.WS), eq(true));
    }

    @Test
    public void successfulLoginAndWs_recordsRequestConnectAndSubscribeStageDurations() throws Exception {
        when(tbClient.logIn()).thenReturn("token");
        when(wsClientFactory.createClient("token")).thenReturn(wsClient);
        when(wsClient.waitForReply()).thenReturn(null);

        service.runChecks();

        verify(probeMetricsRecorder).recordActionDuration(eq(MonitoredServiceKey.LOGIN), eq("request"), anyLong());
        verify(probeMetricsRecorder).recordActionDuration(eq(MonitoredServiceKey.WS), eq("connect"), anyLong());
        verify(probeMetricsRecorder).recordActionDuration(eq(MonitoredServiceKey.WS), eq("subscribe"), anyLong());
    }

    @Test
    public void loginFailure_recordsLoginFailureAndNeverRecordsWs() throws Exception {
        when(tbClient.logIn()).thenThrow(new RuntimeException("login failed"));

        service.runChecks();

        verify(probeMetricsRecorder).recordProbe(eq(MonitoredServiceKey.LOGIN), eq(false));
        verify(probeMetricsRecorder, never()).recordProbe(eq(MonitoredServiceKey.WS), any(Boolean.class));
    }

    @Test
    public void loginFailure_neverRecordsLoginRequestStageDuration() throws Exception {
        when(tbClient.logIn()).thenThrow(new RuntimeException("login failed"));

        service.runChecks();

        verify(probeMetricsRecorder, never()).recordActionDuration(eq(MonitoredServiceKey.LOGIN), eq("request"), anyLong());
    }

    @Test
    public void loginFailure_clearsTransportProbeMetrics() throws Exception {
        // transport checks never ran this cycle - their gauges must not keep reporting last cycle's value
        when(tbClient.logIn()).thenThrow(new RuntimeException("login failed"));

        service.runChecks();

        verify(probeMetricsRecorder, times(1)).removeProbe(any());
    }

    @Test
    public void loginFailure_checksTransportAcceptance() throws Exception {
        when(tbClient.logIn()).thenThrow(new RuntimeException("login failed"));

        service.runChecks();

        verify(healthChecker).checkAccepted();
    }

    @Test
    public void loginFailure_neverRemovesAcceptedProbeBeforeFallbackRuns() throws Exception {
        // removeAcceptedProbe must never race with/precede the fallback check on the failure
        // path - only the success path (per-target, once its fresh E2E result is in) calls it
        when(tbClient.logIn()).thenThrow(new RuntimeException("login failed"));

        service.runChecks();

        verify(probeMetricsRecorder, never()).removeAcceptedProbe(any());
    }

    @Test
    public void wsConnectFailure_recordsWsFailure() throws Exception {
        when(tbClient.logIn()).thenReturn("token");
        when(wsClientFactory.createClient("token")).thenThrow(new RuntimeException("connect failed"));

        service.runChecks();

        verify(probeMetricsRecorder).recordProbe(eq(MonitoredServiceKey.LOGIN), eq(true));
        verify(probeMetricsRecorder).recordProbe(eq(MonitoredServiceKey.WS), eq(false));
    }

    @Test
    public void wsConnectFailure_neverRecordsConnectStageDuration() throws Exception {
        when(tbClient.logIn()).thenReturn("token");
        when(wsClientFactory.createClient("token")).thenThrow(new RuntimeException("connect failed"));

        service.runChecks();

        verify(probeMetricsRecorder, never()).recordActionDuration(eq(MonitoredServiceKey.WS), eq("connect"), anyLong());
    }

    @Test
    public void wsConnectFailure_clearsTransportProbeMetrics() throws Exception {
        when(tbClient.logIn()).thenReturn("token");
        when(wsClientFactory.createClient("token")).thenThrow(new RuntimeException("connect failed"));

        service.runChecks();

        verify(probeMetricsRecorder, times(1)).removeProbe(any());
    }

    @Test
    public void wsConnectFailure_checksTransportAcceptance() throws Exception {
        when(tbClient.logIn()).thenReturn("token");
        when(wsClientFactory.createClient("token")).thenThrow(new RuntimeException("connect failed"));

        service.runChecks();

        verify(healthChecker).checkAccepted();
    }

    @Test
    public void wsConnectFailure_neverRemovesAcceptedProbeBeforeFallbackRuns() throws Exception {
        when(tbClient.logIn()).thenReturn("token");
        when(wsClientFactory.createClient("token")).thenThrow(new RuntimeException("connect failed"));

        service.runChecks();

        verify(probeMetricsRecorder, never()).removeAcceptedProbe(any());
    }

    @Test
    public void wsSubscribeFailure_recordsWsFailure() throws Exception {
        when(tbClient.logIn()).thenReturn("token");
        when(wsClientFactory.createClient("token")).thenReturn(wsClient);
        when(wsClient.waitForReply()).thenThrow(new IllegalStateException("no reply"));

        service.runChecks();

        verify(probeMetricsRecorder).recordProbe(eq(MonitoredServiceKey.WS), eq(false));
    }

    @Test
    public void wsSubscribeFailure_clearsTransportProbeMetrics() throws Exception {
        when(tbClient.logIn()).thenReturn("token");
        when(wsClientFactory.createClient("token")).thenReturn(wsClient);
        when(wsClient.waitForReply()).thenThrow(new IllegalStateException("no reply"));

        service.runChecks();

        verify(probeMetricsRecorder, times(1)).removeProbe(any());
    }

    @Test
    public void wsSubscribeFailure_checksTransportAcceptance() throws Exception {
        when(tbClient.logIn()).thenReturn("token");
        when(wsClientFactory.createClient("token")).thenReturn(wsClient);
        when(wsClient.waitForReply()).thenThrow(new IllegalStateException("no reply"));

        service.runChecks();

        verify(healthChecker).checkAccepted();
    }

    @Test
    public void wsSubscribeFailure_neverRemovesAcceptedProbeBeforeFallbackRuns() throws Exception {
        when(tbClient.logIn()).thenReturn("token");
        when(wsClientFactory.createClient("token")).thenReturn(wsClient);
        when(wsClient.waitForReply()).thenThrow(new IllegalStateException("no reply"));

        service.runChecks();

        verify(probeMetricsRecorder, never()).removeAcceptedProbe(any());
    }

    @Test
    public void successfulRun_neverClearsTransportProbeMetrics() throws Exception {
        when(tbClient.logIn()).thenReturn("token");
        when(wsClientFactory.createClient("token")).thenReturn(wsClient);
        when(wsClient.waitForReply()).thenReturn(null);

        service.runChecks();

        verify(probeMetricsRecorder, never()).removeProbe(any());
    }

    @Test
    public void successfulRun_neverChecksTransportAcceptance() throws Exception {
        // WS is healthy, so the full E2E check already covers this target - checkAccepted() must
        // not also fire, or every healthy cycle would send two test payloads instead of one
        when(tbClient.logIn()).thenReturn("token");
        when(wsClientFactory.createClient("token")).thenReturn(wsClient);
        when(wsClient.waitForReply()).thenReturn(null);

        service.runChecks();

        verify(healthChecker, never()).checkAccepted();
    }

    @Test
    public void successfulRun_removesAcceptedProbeForEachHealthChecker() throws Exception {
        // the E2E check just produced fresh data for this target, so any stale accepted-fallback
        // gauge from an earlier outage cycle must be cleared now rather than freezing forever
        when(tbClient.logIn()).thenReturn("token");
        when(wsClientFactory.createClient("token")).thenReturn(wsClient);
        when(wsClient.waitForReply()).thenReturn(null);

        service.runChecks();

        verify(probeMetricsRecorder, times(1)).removeAcceptedProbe(any());
    }

    @Test
    public void successfulRun_removesAcceptedProbeForAssociatesToo() throws Exception {
        // associates (DNS-resolved IPs of the same target) get their own kind="accepted" gauge via
        // BaseHealthChecker.checkAccepted()'s own recursion during an outage - the recovery-time
        // clear must recurse the same way, or an associate's gauge freezes forever once set
        BaseHealthChecker<TransportMonitoringConfig, TransportMonitoringTarget> associate =
                mock(BaseHealthChecker.class);
        Object associateInfo = new Object();
        when(associate.getCachedInfo()).thenReturn(associateInfo);
        when(healthChecker.getAssociates()).thenReturn(java.util.Map.of("associate-url", associate));

        when(tbClient.logIn()).thenReturn("token");
        when(wsClientFactory.createClient("token")).thenReturn(wsClient);
        when(wsClient.waitForReply()).thenReturn(null);

        service.runChecks();

        verify(probeMetricsRecorder, times(1)).removeAcceptedProbe(associateInfo);
    }

    @Test
    public void metricsDisabled_loginFailure_neverChecksTransportAcceptance() throws Exception {
        // recordAcceptedProbe would no-op anyway when metrics export is disabled (the default) -
        // checkTransportsAccepted's guard is shared code, so this one representative failure
        // branch is enough to cover all 3 call sites
        when(probeMetricsRecorder.isEnabled()).thenReturn(false);
        when(tbClient.logIn()).thenThrow(new RuntimeException("login failed"));

        service.runChecks();

        verify(healthChecker, never()).checkAccepted();
    }

    private static class TestMonitoringService extends BaseMonitoringService<TransportMonitoringConfig, TransportMonitoringTarget> {
        @Override
        protected BaseHealthChecker<?, ?> createHealthChecker(TransportMonitoringConfig config, TransportMonitoringTarget target) {
            throw new UnsupportedOperationException("not exercised in this test");
        }

        @Override
        protected TransportMonitoringTarget createTarget(String baseUrl) {
            TransportMonitoringTarget target = new TransportMonitoringTarget();
            target.setBaseUrl(baseUrl);
            return target;
        }

        @Override
        protected String getName() {
            return "test";
        }
    }

}
