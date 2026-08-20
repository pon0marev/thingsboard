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
package org.thingsboard.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.security.model.IpAllowlistSettings;
import org.thingsboard.server.common.data.security.model.JwtSettings;
import org.thingsboard.server.dao.service.DaoSqlTest;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Slf4j
@DaoSqlTest
public class AdminControllerTest extends AbstractControllerTest {
    final JwtSettings defaultJwtSettings = new JwtSettings(9000, 604800, "thingsboard.io", "QmlicmJkZk9tSzZPVFozcWY0Sm94UVhybmtBWXZ5YmZMOUZSZzZvcUFiOVhsb3VHUThhUWJGaXp3UHhtcGZ6Tw==");

    @Test
    public void testFindAdminSettingsByKey() throws Exception {
        loginSysAdmin();
        doGet("/api/admin/settings/general")
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.key", is("general")))
                .andExpect(jsonPath("$.jsonValue.baseUrl", is("http://localhost:8080")));

        doGet("/api/admin/settings/mail")
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.key", is("mail")))
                .andExpect(jsonPath("$.jsonValue.smtpProtocol", is("smtp")))
                .andExpect(jsonPath("$.jsonValue.smtpHost", is("localhost")))
                .andExpect(jsonPath("$.jsonValue.smtpPort", is("25")));

        doGet("/api/admin/settings/unknown")
                .andExpect(status().isNotFound());

    }

    @Test
    public void testSaveAdminSettings() throws Exception {
        loginSysAdmin();
        AdminSettings adminSettings = doGet("/api/admin/settings/general", AdminSettings.class);

        JsonNode jsonValue = adminSettings.getJsonValue();
        ((ObjectNode) jsonValue).put("baseUrl", "http://myhost.org");
        adminSettings.setJsonValue(jsonValue);

        doPost("/api/admin/settings", adminSettings).andExpect(status().isOk());

        doGet("/api/admin/settings/general")
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$.jsonValue.baseUrl", is("http://myhost.org")));

        ((ObjectNode) jsonValue).put("baseUrl", "http://localhost:8080");
        adminSettings.setJsonValue(jsonValue);

        doPost("/api/admin/settings", adminSettings)
                .andExpect(status().isOk());
    }

    @Test
    public void testSaveAdminSettingsWithEmptyKey() throws Exception {
        loginSysAdmin();
        AdminSettings adminSettings = doGet("/api/admin/settings/mail", AdminSettings.class);
        adminSettings.setKey(null);
        doPost("/api/admin/settings", adminSettings)
                .andExpect(status().isBadRequest())
                .andExpect(statusReason(containsString("Key should be specified")));
    }

    @Test
    public void testChangeAdminSettingsKey() throws Exception {
        loginSysAdmin();
        AdminSettings adminSettings = doGet("/api/admin/settings/mail", AdminSettings.class);
        adminSettings.setKey("newKey");
        doPost("/api/admin/settings", adminSettings)
                .andExpect(status().isBadRequest())
                .andExpect(statusReason(containsString("is prohibited")));
    }

    @Test
    public void testSendTestMail() throws Exception {
        Mockito.doNothing().when(mailService).sendTestMail(any(), anyString());
        loginSysAdmin();
        AdminSettings adminSettings = doGet("/api/admin/settings/mail", AdminSettings.class);
        doPost("/api/admin/settings/testMail", adminSettings)
                .andExpect(status().isOk());
        Mockito.verify(mailService).sendTestMail(Mockito.any(), Mockito.anyString());
    }

    @Test
    public void testSendTestMailTimeout() throws Exception {
        loginSysAdmin();
        AdminSettings adminSettings = doGet("/api/admin/settings/mail", AdminSettings.class);
        ObjectNode objectNode = JacksonUtil.fromString(adminSettings.getJsonValue().toString(), ObjectNode.class);

        objectNode.put("smtpHost", "mail.gandi.net");
        objectNode.put("timeout", 1_000);
        objectNode.put("username", "username");
        objectNode.put("password", "password");

        adminSettings.setJsonValue(objectNode);

        doPost("/api/admin/settings/testMail", adminSettings).andExpect(status().is5xxServerError());
        Mockito.verify(mailService).sendTestMail(Mockito.any(), Mockito.anyString());
    }

    void resetJwtSettingsToDefault() throws Exception {
        loginSysAdmin();
        doPost("/api/admin/jwtSettings", defaultJwtSettings).andExpect(status().isOk()); // jwt test scenarios are always started from
        loginTenantAdmin();
    }

    @Test
    public void testGetAndSaveDefaultJwtSettings() throws Exception {
        JwtSettings jwtSettings;
        loginSysAdmin();

        jwtSettings = doGet("/api/admin/jwtSettings", JwtSettings.class);
        assertThat(jwtSettings).isEqualTo(defaultJwtSettings);

        doPost("/api/admin/jwtSettings", jwtSettings).andExpect(status().isOk());

        jwtSettings = doGet("/api/admin/jwtSettings", JwtSettings.class);
        assertThat(jwtSettings).isEqualTo(defaultJwtSettings);

        resetJwtSettingsToDefault();
    }

    @Test
    public void testCreateJwtSettings() throws Exception {
        loginSysAdmin();

        JwtSettings jwtSettings = doGet("/api/admin/jwtSettings", JwtSettings.class);
        assertThat(jwtSettings).isEqualTo(defaultJwtSettings);

        jwtSettings.setTokenSigningKey(Base64.getEncoder().encodeToString(
                RandomStringUtils.secure().nextAlphanumeric(512 / Byte.SIZE).getBytes(StandardCharsets.UTF_8)));

        doPost("/api/admin/jwtSettings", jwtSettings).andExpect(status().isOk());

        doGet("/api/admin/jwtSettings").andExpect(status().isUnauthorized()); //the old JWT token does not work after signing key was changed!

        loginSysAdmin();
        JwtSettings newJwtSettings = doGet("/api/admin/jwtSettings", JwtSettings.class);
        assertThat(jwtSettings).isEqualTo(newJwtSettings);

        resetJwtSettingsToDefault();
    }

    @Test
    public void testIpAllowlistSettingsRoundTrip() throws Exception {
        loginSysAdmin();

        IpAllowlistSettings initial = doGet("/api/admin/ipAllowlistSettings", IpAllowlistSettings.class);
        Assert.assertTrue(initial.getIpAllowlist() == null || initial.getIpAllowlist().isEmpty());

        // malformed entry rejected at save time, before it ever reaches storage
        IpAllowlistSettings malformed = new IpAllowlistSettings();
        malformed.setIpAllowlist(List.of("not-an-ip-or-cidr"));
        doPost("/api/admin/ipAllowlistSettings", malformed)
                .andExpect(status().isBadRequest())
                .andExpect(statusReason(containsString("Invalid IP address or CIDR block")));

        // transitioning from empty to non-empty from a non-loopback client IP that the new list
        // would exclude - rejected without force=true
        IpAllowlistSettings excludesCaller = new IpAllowlistSettings();
        excludesCaller.setIpAllowlist(List.of("203.0.113.0/24"));
        MockHttpServletRequestBuilder saveFromOutsideIp = post("/api/admin/ipAllowlistSettings");
        setJwtToken(saveFromOutsideIp);
        saveFromOutsideIp.contentType(contentType).content(json(excludesCaller));
        saveFromOutsideIp.with(request -> {
            request.setRemoteAddr("198.51.100.7");
            return request;
        });
        String lockoutError = getErrorMessage(mockMvc.perform(saveFromOutsideIp).andExpect(status().isBadRequest()));
        assertThat(lockoutError).containsIgnoringCase("lock out");

        // same save succeeds once the caller's own IP is included
        IpAllowlistSettings includesCaller = new IpAllowlistSettings();
        includesCaller.setIpAllowlist(List.of("198.51.100.0/24"));
        MockHttpServletRequestBuilder saveIncludingCaller = post("/api/admin/ipAllowlistSettings");
        setJwtToken(saveIncludingCaller);
        saveIncludingCaller.contentType(contentType).content(json(includesCaller));
        saveIncludingCaller.with(request -> {
            request.setRemoteAddr("198.51.100.7");
            return request;
        });
        mockMvc.perform(saveIncludingCaller).andExpect(status().isOk());

        IpAllowlistSettings saved = doGet("/api/admin/ipAllowlistSettings", IpAllowlistSettings.class);
        Assert.assertEquals(List.of("198.51.100.0/24"), saved.getIpAllowlist());

        // edits to an already non-empty list are not re-validated against caller's IP - this should succeed
        // even though the new list (203.0.113.0/24) would exclude the caller (198.51.100.7)
        IpAllowlistSettings editToExcludingList = new IpAllowlistSettings();
        editToExcludingList.setIpAllowlist(List.of("203.0.113.0/24"));
        MockHttpServletRequestBuilder editNonEmptyList = post("/api/admin/ipAllowlistSettings");
        setJwtToken(editNonEmptyList);
        editNonEmptyList.contentType(contentType).content(json(editToExcludingList));
        editNonEmptyList.with(request -> {
            request.setRemoteAddr("198.51.100.7");
            return request;
        });
        mockMvc.perform(editNonEmptyList).andExpect(status().isOk());

        IpAllowlistSettings afterEdit = doGet("/api/admin/ipAllowlistSettings", IpAllowlistSettings.class);
        Assert.assertEquals(List.of("203.0.113.0/24"), afterEdit.getIpAllowlist());

        // force=true override: test requires the list to be empty first (wasEmpty=true is when force is consulted)
        // reset to empty - always allowed regardless of state
        IpAllowlistSettings resetToEmpty = new IpAllowlistSettings();
        resetToEmpty.setIpAllowlist(List.of());
        doPost("/api/admin/ipAllowlistSettings", resetToEmpty).andExpect(status().isOk());

        // verify list is empty before force test
        IpAllowlistSettings emptyVerify = doGet("/api/admin/ipAllowlistSettings", IpAllowlistSettings.class);
        Assert.assertTrue(emptyVerify.getIpAllowlist() == null || emptyVerify.getIpAllowlist().isEmpty());

        // attempt to transition from empty to non-empty excluding list WITHOUT force=true - must fail
        IpAllowlistSettings excludingListForForceTest = new IpAllowlistSettings();
        excludingListForForceTest.setIpAllowlist(List.of("203.0.113.0/24"));
        MockHttpServletRequestBuilder saveExcludingWithoutForce = post("/api/admin/ipAllowlistSettings");
        setJwtToken(saveExcludingWithoutForce);
        saveExcludingWithoutForce.contentType(contentType).content(json(excludingListForForceTest));
        saveExcludingWithoutForce.with(request -> {
            request.setRemoteAddr("198.51.100.7"); // IP NOT in list - would lock out
            return request;
        });
        String forceNeededError = getErrorMessage(mockMvc.perform(saveExcludingWithoutForce).andExpect(status().isBadRequest()));
        assertThat(forceNeededError).containsIgnoringCase("lock out");

        // same transition WITH force=true from same excluded IP must succeed - proves force overrides the guard
        IpAllowlistSettings excludingListWithForce = new IpAllowlistSettings();
        excludingListWithForce.setIpAllowlist(List.of("203.0.113.0/24"));
        MockHttpServletRequestBuilder saveExcludingWithForce = post("/api/admin/ipAllowlistSettings?force=true");
        setJwtToken(saveExcludingWithForce);
        saveExcludingWithForce.contentType(contentType).content(json(excludingListWithForce));
        saveExcludingWithForce.with(request -> {
            request.setRemoteAddr("198.51.100.7"); // same excluded IP
            return request;
        });
        mockMvc.perform(saveExcludingWithForce).andExpect(status().isOk());

        // restore to empty so later tests in this class (and this class's own re-runs) start clean
        IpAllowlistSettings cleared = new IpAllowlistSettings();
        cleared.setIpAllowlist(List.of());
        doPost("/api/admin/ipAllowlistSettings", cleared).andExpect(status().isOk());
    }

}
