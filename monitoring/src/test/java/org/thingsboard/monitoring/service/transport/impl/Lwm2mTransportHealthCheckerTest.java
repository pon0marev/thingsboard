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
package org.thingsboard.monitoring.service.transport.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.monitoring.config.transport.Lwm2mTransportMonitoringConfig;
import org.thingsboard.monitoring.config.transport.TransportMonitoringTarget;
import org.thingsboard.monitoring.metrics.ProbeMetricsRecorder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

public class Lwm2mTransportHealthCheckerTest {

    @Test
    public void checkAccepted_isNoOp_neverRecordsAcceptedProbe() {
        // checkAccepted() is a no-op for LwM2M (see override) - must never call recordAcceptedProbe
        ProbeMetricsRecorder probeMetricsRecorder = mock(ProbeMetricsRecorder.class);
        Lwm2mTransportHealthChecker checker = new Lwm2mTransportHealthChecker(
                new Lwm2mTransportMonitoringConfig(), new TransportMonitoringTarget());
        ReflectionTestUtils.setField(checker, "probeMetricsRecorder", probeMetricsRecorder);

        checker.checkAccepted();

        verifyNoInteractions(probeMetricsRecorder);
    }

}
