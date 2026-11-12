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

import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

public final class IpAllowlistUtils {

    private IpAllowlistUtils() {
    }

    /**
     * Validates that every entry is a well-formed IP address or CIDR block.
     * @throws IllegalArgumentException naming the first invalid entry
     */
    public static void validate(List<String> ipAllowlist) {
        for (String entry : ipAllowlist) {
            try {
                new IpAddressMatcher(entry);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid IP address or CIDR block: " + entry, e);
            }
        }
    }

    // loopback is always allowed (break-glass path for direct server access). Otherwise: if both the
    // DB-managed list and the provisioned (ops-only, config-driven, not editable via the UI/API) list
    // are empty, there's no restriction configured at all - any IP is allowed. As soon as EITHER list
    // is non-empty, access is restricted to the union of whichever list(s) are actually populated -
    // e.g. a non-empty provisioned list with an empty DB-managed list restricts login to provisioned
    // entries only, it does not fall back to "no restriction" the way an empty DB-managed list alone
    // would.
    public static boolean isIpAllowed(List<String> ipAllowlist, List<String> provisionedAllowlist, String clientIp) {
        if (isLoopback(clientIp)) {
            return true;
        }
        if (isEmpty(ipAllowlist) && isEmpty(provisionedAllowlist)) {
            return true;
        }
        return matchesAny(provisionedAllowlist, clientIp) || matchesAny(ipAllowlist, clientIp);
    }

    private static boolean isEmpty(List<String> allowlist) {
        return allowlist == null || allowlist.isEmpty();
    }

    private static boolean matchesAny(List<String> allowlist, String clientIp) {
        return allowlist != null && allowlist.stream().anyMatch(entry -> new IpAddressMatcher(entry).matches(clientIp));
    }

    private static boolean isLoopback(String clientIp) {
        if (clientIp == null) {
            // fail closed - InetAddress.getByName(null) resolves to the loopback address, which
            // would otherwise make a null client address silently bypass the allowlist entirely
            return false;
        }
        try {
            return InetAddress.getByName(clientIp).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

}
