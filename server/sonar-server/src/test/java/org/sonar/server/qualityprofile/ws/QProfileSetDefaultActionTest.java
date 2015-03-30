/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.qualityprofile.ws;

import org.sonar.server.exceptions.ForbiddenException;

import org.apache.commons.lang.StringUtils;
import org.assertj.core.api.Fail;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.sonar.api.resources.AbstractLanguage;
import org.sonar.api.resources.Language;
import org.sonar.api.resources.Languages;
import org.sonar.api.utils.System2;
import org.sonar.core.permission.GlobalPermissions;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.persistence.DbTester;
import org.sonar.core.qualityprofile.db.QualityProfileDao;
import org.sonar.core.qualityprofile.db.QualityProfileDto;
import org.sonar.server.db.DbClient;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.qualityprofile.QProfileFactory;
import org.sonar.server.qualityprofile.QProfileLookup;
import org.sonar.server.user.MockUserSession;
import org.sonar.server.ws.WsTester;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class QProfileSetDefaultActionTest {

  @ClassRule
  public static DbTester dbTester = new DbTester();

  private DbClient dbClient;

  private QualityProfileDao qualityProfileDao;

  private Language xoo1, xoo2;

  private WsTester tester;

  private DbSession session;


  @Before
  public void setUp() throws Exception {
    dbTester.truncateTables();
    qualityProfileDao = new QualityProfileDao(dbTester.myBatis(), mock(System2.class));
    dbClient = new DbClient(dbTester.database(), dbTester.myBatis(), qualityProfileDao);
    session = dbClient.openSession(false);

    xoo1 = createLanguage("xoo1");
    xoo2 = createLanguage("xoo2");
    createProfiles();

    tester = new WsTester(new QProfilesWs(
      mock(RuleActivationActions.class),
      mock(BulkRuleActivationActions.class),
      mock(ProjectAssociationActions.class),
      new QProfileSetDefaultAction(new Languages(xoo1, xoo2), new QProfileLookup(dbClient), new QProfileFactory(dbClient))));
  }

  @After
  public void tearDown() {
    session.close();
  }

  @Test
  public void set_default_profile_using_key() throws Exception {
    MockUserSession.set().setLogin("obiwan").setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN);


    checkDefaultProfile("xoo1", "sonar-way-xoo1-12345");
    checkDefaultProfile("xoo2", "my-sonar-way-xoo2-34567");

    tester.newPostRequest("api/qualityprofiles", "set_default").setParam("profileKey", "sonar-way-xoo2-23456").execute().assertNoContent();

    checkDefaultProfile("xoo1", "sonar-way-xoo1-12345");
    checkDefaultProfile("xoo2", "sonar-way-xoo2-23456");
    assertThat(dbClient.qualityProfileDao().getByKey(session, "sonar-way-xoo2-23456").isDefault()).isTrue();
    assertThat(dbClient.qualityProfileDao().getByKey(session, "my-sonar-way-xoo2-34567").isDefault()).isFalse();

    // One more time!
    tester.newPostRequest("api/qualityprofiles", "set_default").setParam("profileKey", "sonar-way-xoo2-23456").execute().assertNoContent();
    checkDefaultProfile("xoo1", "sonar-way-xoo1-12345");
    checkDefaultProfile("xoo2", "sonar-way-xoo2-23456");
  }

  @Test
  public void set_default_profile_using_language_and_name() throws Exception {
    MockUserSession.set().setLogin("obiwan").setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN);

    tester.newPostRequest("api/qualityprofiles", "set_default").setParam("language", "xoo2").setParam("profileName", "Sonar way").execute().assertNoContent();

    checkDefaultProfile("xoo1", "sonar-way-xoo1-12345");
    checkDefaultProfile("xoo2", "sonar-way-xoo2-23456");
  }

  @Test
  public void fail_to_set_default_profile_using_key() throws Exception {
    MockUserSession.set().setLogin("obiwan").setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN);

    try {
      tester.newPostRequest("api/qualityprofiles", "set_default").setParam("profileKey", "unknown-profile-666").execute();
      Fail.failBecauseExceptionWasNotThrown(IllegalArgumentException.class);
    } catch(IllegalArgumentException nfe) {
      assertThat(nfe).hasMessage("Quality profile not found: unknown-profile-666");
      checkDefaultProfile("xoo1", "sonar-way-xoo1-12345");
      checkDefaultProfile("xoo2", "my-sonar-way-xoo2-34567");
    }
  }


  @Test
  public void fail_to_set_default_profile_using_language_and_name() throws Exception {
    MockUserSession.set().setLogin("obiwan").setGlobalPermissions(GlobalPermissions.QUALITY_PROFILE_ADMIN);

    try {
      tester.newPostRequest("api/qualityprofiles", "set_default").setParam("language", "xoo2").setParam("profileName", "Unknown").execute();
      Fail.failBecauseExceptionWasNotThrown(NotFoundException.class);
    } catch(NotFoundException nfe) {
      assertThat(nfe).hasMessage("Unable to find a profile for language 'xoo2' with name 'Unknown'");
      checkDefaultProfile("xoo1", "sonar-way-xoo1-12345");
      checkDefaultProfile("xoo2", "my-sonar-way-xoo2-34567");
    }
  }

  @Test
  public void fail_on_missing_permission() throws Exception {
    MockUserSession.set().setLogin("obiwan");

    try {
      tester.newPostRequest("api/qualityprofiles", "set_default").setParam("profileKey", "sonar-way-xoo2-23456").execute().assertNoContent();
      Fail.failBecauseExceptionWasNotThrown(ForbiddenException.class);
    } catch(ForbiddenException forbidden) {
      checkDefaultProfile("xoo1", "sonar-way-xoo1-12345");
      checkDefaultProfile("xoo2", "my-sonar-way-xoo2-34567");
    }
  }

  private void createProfiles() {
    qualityProfileDao.insert(session,
      QualityProfileDto.createFor("sonar-way-xoo1-12345").setLanguage(xoo1.getKey()).setName("Sonar way").setDefault(true),
      QualityProfileDto.createFor("sonar-way-xoo2-23456").setLanguage(xoo2.getKey()).setName("Sonar way"),
      QualityProfileDto.createFor("my-sonar-way-xoo2-34567").setLanguage(xoo2.getKey()).setName("My Sonar way").setParentKee("sonar-way-xoo2-23456").setDefault(true)
      );
    session.commit();
  }

  private void checkDefaultProfile(String language, String key) throws Exception {
    assertThat(dbClient.qualityProfileDao().getDefaultProfile(language).getKey()).isEqualTo(key);
  }

  private Language createLanguage(final String key) {
    return new AbstractLanguage(key, StringUtils.capitalize(key)) {
      @Override
      public String[] getFileSuffixes() {
        return new String[] {key};
      }
    };
  }
}
