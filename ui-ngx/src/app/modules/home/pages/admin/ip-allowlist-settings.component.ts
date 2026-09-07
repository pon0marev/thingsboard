///
/// Copyright © 2016-2026 The Thingsboard Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import { Component } from '@angular/core';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { PageComponent } from '@shared/components/page.component';
import { UntypedFormBuilder, UntypedFormGroup } from '@angular/forms';
import { IpAllowlistSettings } from '@shared/models/settings.models';
import { AdminService } from '@core/http/admin.service';
import { HasConfirmForm } from '@core/guards/confirm-on-exit.guard';
import { DialogService } from '@core/services/dialog.service';
import { TranslateService } from '@ngx-translate/core';
import { HttpErrorResponse } from '@angular/common/http';
import { parseHttpErrorMessage } from '@core/utils';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import { Constants } from '@shared/models/constants';

function isValidIpv4(address: string): boolean {
  const parts = address.split('.');
  return parts.length === 4 && parts.every(part =>
    /^\d{1,3}$/.test(part) && Number(part) <= 255 && (part.length === 1 || part[0] !== '0'));
}

function isValidIpv6(address: string): boolean {
  if (!address.includes(':') || (address.match(/::/g) || []).length > 1) {
    return false;
  }
  const ipv4Suffix = /:(\d{1,3}(?:\.\d{1,3}){3})$/.exec(address);
  let hexPart = address;
  let groupCount = 0;
  if (ipv4Suffix) {
    if (!isValidIpv4(ipv4Suffix[1])) {
      return false;
    }
    hexPart = address.slice(0, -ipv4Suffix[1].length - 1);
    groupCount += 2;
  }
  const compressed = hexPart.includes('::');
  const groups = hexPart.split(':').filter(g => g !== '');
  if (!groups.every(g => /^[0-9a-fA-F]{1,4}$/.test(g))) {
    return false;
  }
  groupCount += groups.length;
  return compressed ? groupCount < 8 : groupCount === 8;
}

// best-effort client-side check only - backend's IpAddressMatcher is the authoritative validator
export function isValidIpOrCidr(value: string): boolean {
  const parts = value.split('/');
  if (parts.length > 2) {
    return false;
  }
  const [address, prefix] = parts;
  if (prefix !== undefined && !/^\d{1,3}$/.test(prefix)) {
    return false;
  }
  if (isValidIpv4(address)) {
    return prefix === undefined || Number(prefix) <= 32;
  }
  if (isValidIpv6(address)) {
    return prefix === undefined || Number(prefix) <= 128;
  }
  return false;
}

@Component({
    selector: 'tb-ip-allowlist-settings',
    templateUrl: './ip-allowlist-settings.component.html',
    styleUrls: ['./settings-card.scss', './ip-allowlist-settings.component.scss'],
    standalone: false
})
export class IpAllowlistSettingsComponent extends PageComponent implements HasConfirmForm {

  ipAllowlistFormGroup: UntypedFormGroup;

  showMainLoadingBar = false;

  readonly isValidIpOrCidr = isValidIpOrCidr;

  callerIp: string;

  settingsLoaded = false;

  private ipAllowlistSettings: IpAllowlistSettings;

  constructor(protected store: Store<AppState>,
              private adminService: AdminService,
              private dialogService: DialogService,
              private translate: TranslateService,
              private fb: UntypedFormBuilder) {
    super(store);
    this.ipAllowlistFormGroup = this.fb.group({
      ipAllowlist: [null]
    });
    this.adminService.getIpAllowlistSettings().subscribe(
      settings => {
        this.processIpAllowlistSettings(settings);
        this.settingsLoaded = true;
      }
    );
    // fetched in parallel with the settings above so the page doesn't wait on a second round trip
    this.adminService.getIpAllowlistCallerIp({ignoreLoading: true, ignoreErrors: true}).subscribe(ip => this.callerIp = ip);
  }

  get currentAllowlist(): string[] {
    return this.ipAllowlistFormGroup.get('ipAllowlist').value;
  }

  get isRestricted(): boolean {
    return !!this.currentAllowlist?.length;
  }

  get showCallerIpHint(): boolean {
    // shown regardless of isRestricted - editing an already-restricted list can lock the caller out
    // just as easily as configuring one for the first time, so the hint (and "Add my IP") stays available
    return !!this.callerIp;
  }

  addCallerIp(): void {
    const control = this.ipAllowlistFormGroup.get('ipAllowlist');
    const current: string[] = control.value || [];
    if (!current.includes(this.callerIp)) {
      control.setValue([...current, this.callerIp]);
      control.markAsDirty();
    }
  }

  save(): void {
    const settings: IpAllowlistSettings = {
      ...this.ipAllowlistSettings,
      ...this.ipAllowlistFormGroup.value
    };
    this.saveSettings(settings, false);
  }

  discardSetting() {
    this.ipAllowlistFormGroup.reset(this.ipAllowlistSettings);
  }

  confirmForm(): UntypedFormGroup {
    return this.ipAllowlistFormGroup;
  }

  private saveSettings(settings: IpAllowlistSettings, force: boolean) {
    this.adminService.saveIpAllowlistSettings(settings, force, {ignoreErrors: true}).subscribe({
      next: saved => this.processIpAllowlistSettings(saved),
      error: (error: HttpErrorResponse) => this.handleSaveError(settings, error)
    });
  }

  // the backend rejects a save that would exclude the requester's own current IP with a dedicated
  // error code - everything else (e.g. a malformed CIDR entry) is shown as a plain error
  private handleSaveError(settings: IpAllowlistSettings, error: HttpErrorResponse) {
    const message = parseHttpErrorMessage(error, this.translate).message;
    if (error.error?.errorCode === Constants.serverErrorCode.ipAllowlistLockout) {
      this.dialogService.confirm(
        this.translate.instant('admin.ip-allowlist.lockout-title'),
        message,
        this.translate.instant('action.cancel'),
        this.translate.instant('admin.ip-allowlist.save-anyway')
      ).subscribe(confirmed => {
        if (confirmed) {
          this.saveSettings(settings, true);
        }
      });
    } else {
      this.store.dispatch(new ActionNotificationShow({message, type: 'error'}));
    }
  }

  private processIpAllowlistSettings(settings: IpAllowlistSettings) {
    this.ipAllowlistSettings = settings;
    this.ipAllowlistFormGroup.reset(this.ipAllowlistSettings);
  }
}
