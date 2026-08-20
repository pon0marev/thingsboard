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

@Component({
    selector: 'tb-ip-allowlist-settings',
    templateUrl: './ip-allowlist-settings.component.html',
    styleUrls: ['./settings-card.scss'],
    standalone: false
})
export class IpAllowlistSettingsComponent extends PageComponent implements HasConfirmForm {

  ipAllowlistFormGroup: UntypedFormGroup;

  showMainLoadingBar = false;

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
      settings => this.processIpAllowlistSettings(settings)
    );
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

  // the backend rejects a save that would exclude the requester's own current IP with a specific
  // "lock out" message - everything else (e.g. a malformed CIDR entry) is shown as a plain error
  private handleSaveError(settings: IpAllowlistSettings, error: HttpErrorResponse) {
    const message = parseHttpErrorMessage(error, this.translate).message;
    if (message.toLowerCase().includes('lock out')) {
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
