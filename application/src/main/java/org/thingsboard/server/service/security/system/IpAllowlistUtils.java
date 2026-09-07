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
import java.util.stream.Collectors;

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

    // loopback is always allowed (break-glass path for direct server access), and an empty/null
    // list means "no restriction configured" - both independent of whatever the list contains
    public static boolean isIpAllowed(List<String> ipAllowlist, String clientIp) {
        if (isLoopback(clientIp)) {
            return true;
        }
        if (ipAllowlist == null || ipAllowlist.isEmpty()) {
            return true;
        }
        return ipAllowlist.stream().anyMatch(entry -> new IpAddressMatcher(entry).matches(clientIp));
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
