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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.server.queue.util.TbCoreComponent;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * IPs/CIDR blocks always allowed to log in as a SYS_ADMIN, configured via the
 * 'security.sysadmin_ip_allowlist.provisioned' property (env SECURITY_SYSADMIN_IP_ALLOWLIST_PROVISIONED).
 * Unlike the DB-managed allowlist, this is not editable via the UI/API - it's an ops-only escape hatch
 * so admins are never locked out by their own allowlist changes, even where the real loopback address
 * never reaches the app (e.g. behind Docker's port-published NAT).
 */
@Component
@TbCoreComponent
public class ProvisionedIpAllowlist {

    private final List<String> entries;

    public ProvisionedIpAllowlist(@Value("${security.sysadmin_ip_allowlist.provisioned:}") String provisioned) {
        this.entries = Arrays.stream(provisioned.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        IpAllowlistUtils.validate(entries);
    }

    public List<String> get() {
        return entries;
    }

}
