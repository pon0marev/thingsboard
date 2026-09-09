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
package org.thingsboard.server.service.security.system;

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ProvisionedIpAllowlistTest {

    @Test
    public void emptyString_parsesToEmptyList() {
        assertThat(new ProvisionedIpAllowlist("").get()).isEmpty();
    }

    @Test
    public void singleEntry_parsesCorrectly() {
        assertThat(new ProvisionedIpAllowlist("172.18.0.1").get()).containsExactly("172.18.0.1");
    }

    @Test
    public void multipleEntries_areAllParsed() {
        assertThat(new ProvisionedIpAllowlist("172.18.0.1,8.8.8.8,203.0.113.0/24").get())
                .containsExactly("172.18.0.1", "8.8.8.8", "203.0.113.0/24");
    }

    @Test
    public void whitespaceAroundEntries_isTrimmed() {
        assertThat(new ProvisionedIpAllowlist(" 172.18.0.1 , 8.8.8.8 ").get())
                .containsExactly("172.18.0.1", "8.8.8.8");
    }

    @Test
    public void emptySegmentsFromExtraCommas_areDropped() {
        // a trailing/leading/doubled comma produces an empty split segment - it should be
        // dropped rather than kept as a blank entry or rejected as an invalid IP
        assertThat(new ProvisionedIpAllowlist("172.18.0.1,,8.8.8.8,").get())
                .containsExactly("172.18.0.1", "8.8.8.8");
    }

    @Test
    public void malformedEntry_failsFastAtConstruction() {
        assertThatThrownBy(() -> new ProvisionedIpAllowlist("not-an-ip"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-an-ip");
    }

    @Test
    public void oneMalformedEntryAmongValidOnes_stillFailsFast() {
        assertThatThrownBy(() -> new ProvisionedIpAllowlist("172.18.0.1,not-an-ip"))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
