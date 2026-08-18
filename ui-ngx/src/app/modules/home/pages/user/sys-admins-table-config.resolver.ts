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

import { Injectable } from '@angular/core';
import { ActivatedRouteSnapshot, Router } from '@angular/router';
import {
  DateEntityTableColumn,
  EntityTableColumn,
  EntityTableConfig
} from '@home/models/entity/entities-table-config.models';
import { TranslateService } from '@ngx-translate/core';
import { DatePipe } from '@angular/common';
import { EntityType, entityTypeResources, entityTypeTranslations } from '@shared/models/entity-type.models';
import { User } from '@shared/models/user.model';
import { UserService } from '@core/http/user.service';
import { UserComponent } from '@modules/home/pages/user/user.component';
import { UserTabsComponent } from '@home/pages/user/user-tabs.component';
import { map, take, tap } from 'rxjs/operators';
import { Observable } from 'rxjs';
import { Authority } from '@shared/models/authority.enum';
import { CustomerId } from '@shared/models/id/customer-id';
import { MatDialog } from '@angular/material/dialog';
import { EntityAction } from '@home/models/entity/entity-component.models';
import { AddUserDialogComponent, AddUserDialogData } from '@modules/home/pages/user/add-user-dialog.component';
import { select, Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { selectAuth } from '@core/auth/auth.selectors';
import {
  ActivationLinkDialogComponent,
  ActivationLinkDialogData
} from '@modules/home/pages/user/activation-link-dialog.component';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import { NULL_UUID } from '@shared/models/id/has-uuid';
import { TenantId } from '@app/shared/models/id/tenant-id';

@Injectable()
export class SysAdminsTableConfigResolver {

  private readonly config: EntityTableConfig<User> = new EntityTableConfig<User>();

  private authUser: User;

  constructor(private store: Store<AppState>,
              private userService: UserService,
              private translate: TranslateService,
              private datePipe: DatePipe,
              private router: Router,
              private dialog: MatDialog) {

    this.config.entityType = EntityType.USER;
    this.config.entityComponent = UserComponent;
    this.config.entityTabsComponent = UserTabsComponent;
    this.config.entityTranslations = entityTypeTranslations.get(EntityType.USER);
    this.config.entityResources = entityTypeResources.get(EntityType.USER);
    this.config.tableTitle = this.translate.instant('user.sys-admins');

    this.config.columns.push(
      new DateEntityTableColumn<User>('createdTime', 'common.created-time', this.datePipe, '150px'),
      new EntityTableColumn<User>('firstName', 'user.first-name', '33%'),
      new EntityTableColumn<User>('lastName', 'user.last-name', '33%'),
      new EntityTableColumn<User>('email', 'user.email', '33%')
    );

    this.config.deleteEnabled = user => user && user.id && user.id.id !== this.authUser.id.id;
    this.config.deleteEntityTitle = user => this.translate.instant('user.delete-user-title', { userEmail: user.email });
    this.config.deleteEntityContent = () => this.translate.instant('user.delete-user-text');
    this.config.deleteEntitiesTitle = count => this.translate.instant('user.delete-users-title', {count});
    this.config.deleteEntitiesContent = () => this.translate.instant('user.delete-users-text');

    this.config.loadEntity = id => this.userService.getUser(id.id);
    this.config.saveEntity = user => this.saveUser(user);
    this.config.deleteEntity = id => this.userService.deleteUser(id.id);
    this.config.onEntityAction = action => this.onUserAction(action, this.config);
    this.config.addEntity = () => this.addUser();
    this.config.entitiesFetchFunction = pageLink => this.userService.getSysAdmins(pageLink);
  }

  resolve(route: ActivatedRouteSnapshot): Observable<EntityTableConfig<User>> {
    return this.store.pipe(select(selectAuth), take(1)).pipe(
      tap((auth) => {
        this.authUser = auth.userDetails;
      }),
      map(() => this.config)
    );
  }

  saveUser(user: User): Observable<User> {
    user.tenantId = new TenantId(NULL_UUID);
    user.customerId = new CustomerId(NULL_UUID);
    user.authority = Authority.SYS_ADMIN;
    if (!user.additionalInfo.lang) {
      delete user.additionalInfo.lang;
    }
    if (!user.additionalInfo.unitSystem) {
      delete user.additionalInfo.unitSystem;
    }
    return this.userService.saveUser(user);
  }

  addUser(): Observable<User> {
    return this.dialog.open<AddUserDialogComponent, AddUserDialogData,
      User>(AddUserDialogComponent, {
      disableClose: true,
      panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
      data: {
        tenantId: NULL_UUID,
        customerId: NULL_UUID,
        authority: Authority.SYS_ADMIN
      }
    }).afterClosed();
  }

  private openUser($event: Event, user: User, config: EntityTableConfig<User>) {
    if ($event) {
      $event.stopPropagation();
    }
    const url = this.router.createUrlTree([user.id.id], {relativeTo: config.getActivatedRoute()});
    this.router.navigateByUrl(url);
  }

  displayActivationLink($event: Event, user: User) {
    if ($event) {
      $event.stopPropagation();
    }
    this.userService.getActivationLinkInfo(user.id.id).subscribe(
      (activationLinkInfo) => {
        this.dialog.open<ActivationLinkDialogComponent, ActivationLinkDialogData,
          void>(ActivationLinkDialogComponent, {
          disableClose: true,
          panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
          data: {
            activationLinkInfo
          }
        });
      }
    );
  }

  resendActivation($event: Event, user: User) {
    if ($event) {
      $event.stopPropagation();
    }
    this.userService.sendActivationEmail(user.email).subscribe(() => {
      this.store.dispatch(new ActionNotificationShow(
        {
          message: this.translate.instant('user.activation-email-sent-message'),
          type: 'success'
        }));
    });
  }

  setUserCredentialsEnabled($event: Event, user: User, userCredentialsEnabled: boolean) {
    if ($event) {
      $event.stopPropagation();
    }
    this.userService.setUserCredentialsEnabled(user.id.id, userCredentialsEnabled).subscribe(() => {
      if (!user.additionalInfo) {
        user.additionalInfo = {};
      }
      user.additionalInfo.userCredentialsEnabled = userCredentialsEnabled;
      this.store.dispatch(new ActionNotificationShow(
        {
          message: this.translate.instant(userCredentialsEnabled ? 'user.enable-account-message' : 'user.disable-account-message'),
          type: 'success'
        }));
    });
  }

  onUserAction(action: EntityAction<User>, config: EntityTableConfig<User>): boolean {
    switch (action.action) {
      case 'open':
        this.openUser(action.event, action.entity, config);
        return true;
      case 'displayActivationLink':
        this.displayActivationLink(action.event, action.entity);
        return true;
      case 'resendActivation':
        this.resendActivation(action.event, action.entity);
        return true;
      case 'disableAccount':
        this.setUserCredentialsEnabled(action.event, action.entity, false);
        return true;
      case 'enableAccount':
        this.setUserCredentialsEnabled(action.event, action.entity, true);
        return true;
    }
    return false;
  }

}
