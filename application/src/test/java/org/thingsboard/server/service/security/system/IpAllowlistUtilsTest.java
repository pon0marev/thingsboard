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

public class IpAllowlistUtilsTest {

    @Test
    public void loopbackIsAlwaysAllowed_evenWithARestrictiveList() {
        List<String> restrictive = List.of("203.0.113.0/24");
        assertThat(IpAllowlistUtils.isIpAllowed(restrictive, List.of(), "127.0.0.1")).isTrue();
        assertThat(IpAllowlistUtils.isIpAllowed(restrictive, List.of(), "::1")).isTrue();
    }

    @Test
    public void nullOrEmptyAllowlist_allowsAnyIp() {
        assertThat(IpAllowlistUtils.isIpAllowed(null, List.of(), "8.8.8.8")).isTrue();
        assertThat(IpAllowlistUtils.isIpAllowed(List.of(), List.of(), "8.8.8.8")).isTrue();
    }

    @Test
    public void matchingCidrEntry_isAllowed() {
        assertThat(IpAllowlistUtils.isIpAllowed(List.of("203.0.113.0/24"), List.of(), "203.0.113.42")).isTrue();
    }

    @Test
    public void matchingSingleIpEntry_isAllowed() {
        assertThat(IpAllowlistUtils.isIpAllowed(List.of("203.0.113.42"), List.of(), "203.0.113.42")).isTrue();
    }

    @Test
    public void nonMatchingEntry_isDenied() {
        assertThat(IpAllowlistUtils.isIpAllowed(List.of("203.0.113.0/24"), List.of(), "198.51.100.7")).isFalse();
    }

    @Test
    public void matchesAnyOfMultipleEntries() {
        List<String> allowlist = List.of("198.51.100.0/24", "203.0.113.42");
        assertThat(IpAllowlistUtils.isIpAllowed(allowlist, List.of(), "203.0.113.42")).isTrue();
        assertThat(IpAllowlistUtils.isIpAllowed(allowlist, List.of(), "198.51.100.5")).isTrue();
        assertThat(IpAllowlistUtils.isIpAllowed(allowlist, List.of(), "192.0.2.1")).isFalse();
    }

    @Test
    public void nullClientIp_failsClosedAgainstARestrictiveList() {
        // regression test: InetAddress.getByName(null) resolves to the loopback address, so a naive
        // implementation would treat a null client IP as loopback and bypass the allowlist entirely
        assertThat(IpAllowlistUtils.isIpAllowed(List.of("203.0.113.0/24"), List.of(), null)).isFalse();
    }

    @Test
    public void provisionedEntry_isAllowed_evenWhenExcludedFromTheDbManagedList() {
        List<String> restrictive = List.of("203.0.113.0/24");
        List<String> provisioned = List.of("192.0.2.99");
        assertThat(IpAllowlistUtils.isIpAllowed(restrictive, provisioned, "192.0.2.99")).isTrue();
    }

    @Test
    public void provisionedEntry_doesNotWidenAccessForOtherIps() {
        List<String> restrictive = List.of("203.0.113.0/24");
        List<String> provisioned = List.of("192.0.2.99");
        assertThat(IpAllowlistUtils.isIpAllowed(restrictive, provisioned, "198.51.100.7")).isFalse();
    }

}
