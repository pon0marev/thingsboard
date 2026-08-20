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
package org.thingsboard.server.dao.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.security.model.IpAllowlistSettings;

import static org.thingsboard.server.common.data.CacheConstants.IP_ALLOWLIST_SETTINGS_CACHE;

@Service
@RequiredArgsConstructor
public class DefaultIpAllowlistSettingsService implements IpAllowlistSettingsService {

    private static final String SETTINGS_KEY = "ipAllowlistSettings";

    private final AdminSettingsService adminSettingsService;

    @Cacheable(cacheNames = IP_ALLOWLIST_SETTINGS_CACHE, key = "'ipAllowlistSettings'")
    @Override
    public IpAllowlistSettings getIpAllowlistSettings() {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByKey(TenantId.SYS_TENANT_ID, SETTINGS_KEY);
        if (adminSettings != null) {
            try {
                return JacksonUtil.convertValue(adminSettings.getJsonValue(), IpAllowlistSettings.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load IP allowlist settings!", e);
            }
        }
        return new IpAllowlistSettings();
    }

    @CacheEvict(cacheNames = IP_ALLOWLIST_SETTINGS_CACHE, key = "'ipAllowlistSettings'")
    @Override
    public IpAllowlistSettings saveIpAllowlistSettings(IpAllowlistSettings ipAllowlistSettings) {
        AdminSettings adminSettings = adminSettingsService.findAdminSettingsByKey(TenantId.SYS_TENANT_ID, SETTINGS_KEY);
        if (adminSettings == null) {
            adminSettings = new AdminSettings();
            adminSettings.setTenantId(TenantId.SYS_TENANT_ID);
            adminSettings.setKey(SETTINGS_KEY);
        }
        adminSettings.setJsonValue(JacksonUtil.valueToTree(ipAllowlistSettings));
        AdminSettings savedAdminSettings = adminSettingsService.saveAdminSettings(TenantId.SYS_TENANT_ID, adminSettings);
        try {
            return JacksonUtil.convertValue(savedAdminSettings.getJsonValue(), IpAllowlistSettings.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load IP allowlist settings!", e);
        }
    }

}
