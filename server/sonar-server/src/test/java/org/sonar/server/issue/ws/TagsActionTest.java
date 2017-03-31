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
package org.sonar.server.issue.ws;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.config.MapSettings;
import org.sonar.api.server.ws.WebService.Action;
import org.sonar.api.server.ws.WebService.Param;
import org.sonar.api.utils.System2;
import org.sonar.db.DbTester;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.issue.IssueDto;
import org.sonar.db.issue.IssueTesting;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.rule.RuleDto;
import org.sonar.server.es.EsTester;
import org.sonar.server.issue.index.IssueIndex;
import org.sonar.server.issue.index.IssueIndexDefinition;
import org.sonar.server.issue.index.IssueIndexer;
import org.sonar.server.issue.index.IssueIteratorFactory;
import org.sonar.server.organization.TestDefaultOrganizationProvider;
import org.sonar.server.permission.index.AuthorizationTypeSupport;
import org.sonar.server.rule.index.RuleIndexDefinition;
import org.sonar.server.rule.index.RuleIndexer;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.ws.WsActionTester;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.sonar.api.web.UserRole.USER;
import static org.sonar.db.component.ComponentTesting.newFileDto;
import static org.sonar.db.rule.RuleTesting.setSystemTags;
import static org.sonar.db.rule.RuleTesting.setTags;
import static org.sonar.test.JsonAssert.assertJson;

public class TagsActionTest {

  @Rule
  public UserSessionRule userSession = UserSessionRule.standalone();
  @Rule
  public DbTester db = DbTester.create();
  @Rule
  public EsTester es = new EsTester(new IssueIndexDefinition(new MapSettings()), new RuleIndexDefinition(new MapSettings()));

  private IssueIndexer issueIndexer = new IssueIndexer(es.client(), new IssueIteratorFactory(db.getDbClient()));
  private RuleIndexer ruleIndexer = new RuleIndexer(es.client(), db.getDbClient());
  private IssueIndex issueIndex = new IssueIndex(es.client(), System2.INSTANCE, userSession, new AuthorizationTypeSupport(userSession));

  private WsActionTester tester = new WsActionTester(new TagsAction(issueIndex, TestDefaultOrganizationProvider.from(db)));
  private OrganizationDto organization;

  @Before
  public void before() {
    organization = db.getDefaultOrganization();
  }

  @Test
  public void return_tags_from_issues() throws Exception {
    insertIssueWithBrowsePermission(insertRuleWithoutTags(), "tag1", "tag2");
    insertIssueWithBrowsePermission(insertRuleWithoutTags(), "tag3", "tag4", "tag5");
    issueIndexer.indexOnStartup(null);

    String result = tester.newRequest().execute().getInput();
    assertJson(result).isSimilarTo("{\"tags\":[\"tag1\", \"tag2\", \"tag3\", \"tag4\", \"tag5\"]}");
  }

  @Test
  public void return_tags_from_rules() throws Exception {
    RuleDto r = db.rules().insertRule(organization,
      setSystemTags("tag1"),
      setTags("tag2"));
    db.commit();
    ruleIndexer.indexRuleDefinitions(asList(r.getDefinition().getKey()));
    ruleIndexer.indexRuleExtension(organization, r.getKey());
    RuleDto r2 = db.rules().insertRule(organization,
      setSystemTags("tag3"),
      setTags("tag4", "tag5"));
    db.commit();
    ruleIndexer.indexRuleDefinitions(asList(r2.getDefinition().getKey()));
    ruleIndexer.indexRuleExtension(organization, r2.getKey());

    String result = tester.newRequest().execute().getInput();
    assertJson(result).isSimilarTo("{\"tags\":[\"tag1\", \"tag2\", \"tag3\", \"tag4\", \"tag5\"]}");
  }

  @Test
  public void return_tags_from_issue_and_rule_tags() throws Exception {
    insertIssueWithBrowsePermission(insertRuleWithoutTags(), "tag1", "tag2");
    insertIssueWithBrowsePermission(insertRuleWithoutTags(), "tag3", "tag4", "tag5");
    issueIndexer.indexOnStartup(null);

    RuleDto r = db.rules().insertRule(organization,
      setSystemTags("tag6"),
      setTags("tag7"));
    db.commit();
    ruleIndexer.indexRuleDefinitions(asList(r.getDefinition().getKey()));
    ruleIndexer.indexRuleExtension(organization, r.getKey());

    String result = tester.newRequest().execute().getInput();
    assertJson(result).isSimilarTo("{\"tags\":[\"tag1\", \"tag2\", \"tag3\", \"tag4\", \"tag5\", \"tag6\", \"tag7\"]}");
  }

  @Test
  public void return_limited_size() throws Exception {
    insertIssueWithBrowsePermission(insertRuleWithoutTags(), "tag1", "tag2");
    insertIssueWithBrowsePermission(insertRuleWithoutTags(), "tag3", "tag4", "tag5");
    issueIndexer.indexOnStartup(null);

    String result = tester.newRequest().setParam("ps", "2").execute().getInput();
    assertJson(result).isSimilarTo("{\"tags\":[\"tag1\", \"tag2\"]}");
  }

  @Test
  public void return_tags_matching_query() throws Exception {
    insertIssueWithBrowsePermission(insertRuleWithoutTags(), "tag1", "tag2");
    insertIssueWithBrowsePermission(insertRuleWithoutTags(), "tag12", "tag4", "tag5");
    issueIndexer.indexOnStartup(null);

    String result = tester.newRequest().setParam("q", "ag1").execute().getInput();
    assertJson(result).isSimilarTo("{\"tags\":[\"tag1\", \"tag12\"]}");
  }

  @Test
  public void return_empty_list() throws Exception {
    String result = tester.newRequest().execute().getInput();
    assertJson(result).isSimilarTo("{\"tags\":[]}");
  }

  @Test
  public void test_example() throws Exception {
    insertIssueWithBrowsePermission(insertRuleWithoutTags(), "convention");
    issueIndexer.indexOnStartup(null);

    RuleDto r = db.rules().insertRule(organization,
      setSystemTags("cwe"),
      setTags("security"));
    db.commit();
    ruleIndexer.indexRuleDefinitions(asList(r.getDefinition().getKey()));
    ruleIndexer.indexRuleExtension(organization, r.getKey());

    String result = tester.newRequest().execute().getInput();
    assertJson(result).isSimilarTo(tester.getDef().responseExampleAsString());
  }

  @Test
  public void test_definition() {
    Action action = tester.getDef();
    assertThat(action.description()).isNotEmpty();
    assertThat(action.responseExampleAsString()).isNotEmpty();
    assertThat(action.isPost()).isFalse();
    assertThat(action.isInternal()).isFalse();
    assertThat(action.params()).hasSize(2);

    Param query = action.param("q");
    assertThat(query).isNotNull();
    assertThat(query.isRequired()).isFalse();
    assertThat(query.description()).isNotEmpty();
    assertThat(query.exampleValue()).isNotEmpty();

    Param pageSize = action.param("ps");
    assertThat(pageSize).isNotNull();
    assertThat(pageSize.isRequired()).isFalse();
    assertThat(pageSize.defaultValue()).isEqualTo("10");
    assertThat(pageSize.description()).isNotEmpty();
    assertThat(pageSize.exampleValue()).isNotEmpty();
  }

  private RuleDto insertRuleWithoutTags() {
    RuleDto ruleDto = db.rules().insertRule(organization,
      setSystemTags(),
      setTags());
    return ruleDto;
  }

  private IssueDto insertIssue(RuleDto rule, String... tags) {
    ComponentDto project = db.components().insertProject(organization);
    ComponentDto file = db.components().insertComponent(newFileDto(project));
    IssueDto issueDto = IssueTesting.newDto(rule, file, project).setTags(asList(tags));
    return db.issues().insertIssue(issueDto);
  }

  private void setUserWithBrowsePermission(IssueDto issue) {
    userSession.logIn("john").addProjectUuidPermissions(USER, issue.getProjectUuid());
  }

  private IssueDto insertIssueWithBrowsePermission(RuleDto rule, String... tags) {
    IssueDto issue = insertIssue(rule, tags);
    setUserWithBrowsePermission(issue);
    return issue;
  }
}
