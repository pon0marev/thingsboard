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
import { isValidIpOrCidr, parseHttpErrorMessage } from '@core/utils';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import { Constants } from '@shared/models/constants';

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
    // restricted as soon as EITHER list is non-empty - a provisioned-only list (editable list still
    // empty) restricts login on its own, it doesn't fall back to "no restriction"
    return !!this.currentAllowlist?.length || !!this.provisionedIpAllowlist.length;
  }

  get provisionedIpAllowlist(): string[] {
    return this.ipAllowlistSettings?.provisionedIpAllowlist ?? [];
  }

  get allowedNetworksCount(): number {
    // dedupe in case the same entry appears in both lists, so it isn't counted twice
    return new Set([...(this.currentAllowlist ?? []), ...this.provisionedIpAllowlist]).size;
  }

  get showCallerIpHint(): boolean {
    // shown regardless of isRestricted (not just when the list is empty) - editing an already-restricted
    // list can lock the caller out just as easily as configuring one for the first time. But once the
    // caller's own IP is actually in the editable list, or already covered by the provisioned list
    // (always allowed, server-configured), there's no lockout risk left to flag
    return !!this.callerIp
      && !this.currentAllowlist?.includes(this.callerIp)
      && !this.provisionedIpAllowlist.includes(this.callerIp);
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
