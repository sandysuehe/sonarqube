/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
//@flow
import { getJSON, post } from '../helpers/request';

export function searchUsersGroups(query?: string, organization?: string) {
  const url = '/api/user_groups/search';
  const data: { q?: string, organization?: string } = {};
  if (query) {
    data.q = query;
  }
  if (organization) {
    data.organization = organization;
  }
  return getJSON(url, data);
}

export function addUserToGroup(groupId: string, login: string) {
  const url = '/api/user_groups/add_user';
  return post(url, { id: groupId, login });
}

export function removeUserFromGroup(groupId: string, login: string) {
  const url = '/api/user_groups/remove_user';
  return post(url, { id: groupId, login });
}
