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
package org.sonar.server.rule.index;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.config.MapSettings;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rule.RuleStatus;
import org.sonar.api.utils.System2;
import org.sonar.db.DbTester;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.qualityprofile.ActiveRuleKey;
import org.sonar.db.rule.RuleDto;
import org.sonar.db.rule.RuleTesting;
import org.sonar.server.es.EsTester;
import org.sonar.server.es.SearchIdResult;
import org.sonar.server.es.SearchOptions;
import org.sonar.server.qualityprofile.index.ActiveRuleDoc;
import org.sonar.server.qualityprofile.index.ActiveRuleDocTesting;
import org.sonar.server.qualityprofile.index.ActiveRuleIndexer;

import static com.google.common.collect.ImmutableSet.of;
import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.MapEntry.entry;
import static org.junit.Assert.fail;
import static org.sonar.api.rule.Severity.BLOCKER;
import static org.sonar.api.rule.Severity.CRITICAL;
import static org.sonar.api.rule.Severity.INFO;
import static org.sonar.api.rule.Severity.MAJOR;
import static org.sonar.api.rule.Severity.MINOR;
import static org.sonar.api.rules.RuleType.BUG;
import static org.sonar.api.rules.RuleType.CODE_SMELL;
import static org.sonar.api.rules.RuleType.VULNERABILITY;
import static org.sonar.server.qualityprofile.ActiveRule.Inheritance.INHERITED;
import static org.sonar.server.qualityprofile.ActiveRule.Inheritance.OVERRIDES;
import static org.sonar.server.rule.index.RuleIndexDefinition.INDEX_TYPE_ACTIVE_RULE;

public class RuleIndexTest {

  private static final RuleKey RULE_KEY_1 = RuleTesting.XOO_X1;
  private static final RuleKey RULE_KEY_2 = RuleTesting.XOO_X2;
  private static final RuleKey RULE_KEY_3 = RuleTesting.XOO_X3;
  private static final RuleKey RULE_KEY_4 = RuleKey.of("xoo", "x4");
  private static final String QUALITY_PROFILE_KEY1 = "qp1";
  private static final String QUALITY_PROFILE_KEY2 = "qp2";

  private System2 system2 = System2.INSTANCE;

  @Rule
  public EsTester tester = new EsTester(new RuleIndexDefinition(new MapSettings()));
  @Rule
  public DbTester dbTester = DbTester.create(system2);

  private RuleIndex index;
  private RuleIndexer ruleIndexer;
  private ActiveRuleIndexer activeRuleIndexer;

  @Before
  public void setUp() {
    ruleIndexer = new RuleIndexer(tester.client(), dbTester.getDbClient());
    activeRuleIndexer = new ActiveRuleIndexer(system2, dbTester.getDbClient(), tester.client());
    index = new RuleIndex(tester.client());
  }

  @Test
  public void search_all_rules() {
    createRule();
    createRule();

    SearchIdResult results = index.search(new RuleQuery(), new SearchOptions());

    assertThat(results.getTotal()).isEqualTo(2);
    assertThat(results.getIds()).hasSize(2);
  }

  @Test
  public void search_by_key() {
    RuleDto js1 = createRule(
      rule -> rule.setRepositoryKey("javascript"),
      rule -> rule.setRuleKey("X001"));
    RuleDto cobol1 = createRule(
      rule -> rule.setRepositoryKey("cobol"),
      rule -> rule.setRuleKey("X001"));
    RuleDto php2 = createRule(
      rule -> rule.setRepositoryKey("php"),
      rule -> rule.setRuleKey("S002"));

    // key
    RuleQuery query = new RuleQuery().setQueryText("X001");
    assertThat(index.search(query, new SearchOptions()).getIds()).containsOnly(js1.getKey(), cobol1.getKey());

    // partial key does not match
    query = new RuleQuery().setQueryText("X00");
    assertThat(index.search(query, new SearchOptions()).getIds()).isEmpty();

    // repo:key -> nice-to-have !
    query = new RuleQuery().setQueryText("javascript:X001");
    assertThat(index.search(query, new SearchOptions()).getIds()).containsOnly(js1.getKey());
  }

  @Test
  public void search_by_case_insensitive_key() {
    RuleDto ruleDto = createRule(
      rule -> rule.setRepositoryKey("javascript"),
      rule -> rule.setRuleKey("X001"));

    RuleQuery query = new RuleQuery().setQueryText("x001");
    assertThat(index.search(query, new SearchOptions()).getIds()).containsOnly(ruleDto.getKey());
  }

  @Test
  public void filter_by_key() {
    createRule(
      rule -> rule.setRepositoryKey("javascript"),
      rule -> rule.setRuleKey("X001"));
    createRule(
      rule -> rule.setRepositoryKey("cobol"),
      rule -> rule.setRuleKey("X001"));
    createRule(
      rule -> rule.setRepositoryKey("php"),
      rule -> rule.setRuleKey("S002"));

    // key
    RuleQuery query = new RuleQuery().setKey(RuleKey.of("javascript", "X001").toString());

    assertThat(index.search(query, new SearchOptions()).getIds()).hasSize(1);

    // partial key does not match
    query = new RuleQuery().setKey("X001");
    assertThat(index.search(query, new SearchOptions()).getIds()).isEmpty();
  }

  @Test
  public void search_name_by_query() {
    createRule(
      rule -> rule.setName("testing the partial match and matching of rule"));

    // substring
    RuleQuery query = new RuleQuery().setQueryText("test");
    assertThat(index.search(query, new SearchOptions()).getIds()).hasSize(1);

    // substring
    query = new RuleQuery().setQueryText("partial match");
    assertThat(index.search(query, new SearchOptions()).getIds()).hasSize(1);

    // case-insensitive
    query = new RuleQuery().setQueryText("TESTING");
    assertThat(index.search(query, new SearchOptions()).getIds()).hasSize(1);

    // not found
    query = new RuleQuery().setQueryText("not present");
    assertThat(index.search(query, new SearchOptions()).getIds()).isEmpty();
  }

  @Test
  public void search_name_with_protected_chars() {
    String nameWithProtectedChars = "ja#va&sc\"r:ipt";

    RuleDto ruleDto = createRule(
      rule -> rule.setName(nameWithProtectedChars));

    RuleQuery protectedCharsQuery = new RuleQuery().setQueryText(nameWithProtectedChars);
    List<RuleKey> results = index.search(protectedCharsQuery, new SearchOptions()).getIds();
    assertThat(results).containsOnly(ruleDto.getKey());
  }

  @Test
  public void search_by_any_of_repositories() {
    RuleDto findbugs = createRule(
      rule -> rule.setRepositoryKey("findbugs"),
      rule -> rule.setRuleKey("S001"));
    RuleDto pmd = createRule(
      rule -> rule.setRepositoryKey("pmd"),
      rule -> rule.setRuleKey("S002"));

    RuleQuery query = new RuleQuery().setRepositories(asList("checkstyle", "pmd"));
    SearchIdResult results = index.search(query, new SearchOptions());
    assertThat(results.getIds()).containsOnly(pmd.getKey());

    // no results
    query = new RuleQuery().setRepositories(singletonList("checkstyle"));
    assertThat(index.search(query, new SearchOptions()).getIds()).isEmpty();

    // empty list => no filter
    query = new RuleQuery().setRepositories(Collections.emptyList());
    assertThat(index.search(query, new SearchOptions()).getIds()).containsOnly(findbugs.getKey(), pmd.getKey());
  }

  @Test
  public void filter_by_tags() {
    RuleDto rule1 = createRule(
      rule -> rule.setTags(singleton("tag1")),
      rule -> rule.setSystemTags(singleton("tag1s")));
    RuleDto rule2 = createRule(
      rule -> rule.setTags(singleton("tag2")),
      rule -> rule.setSystemTags(singleton("tag2s")));

    // find all
    RuleQuery query = new RuleQuery();
    assertThat(index.search(query, new SearchOptions()).getIds()).containsOnly(rule1.getKey(), rule2.getKey());

    // tag2 in filter
    query = new RuleQuery().setTags(of("tag2"));
    assertThat(index.search(query, new SearchOptions()).getIds()).containsOnly(rule2.getKey());

    // tag2 in filter and tag1 tag2 in query
    query = new RuleQuery().setTags(of("tag2")).setQueryText("tag1");
    assertThat(index.search(query, new SearchOptions()).getIds()).containsOnly();

    // tag2 in filter and tag1 in query
    query = new RuleQuery().setTags(of("tag2")).setQueryText("tag1 tag2");
    assertThat(index.search(query, new SearchOptions()).getIds()).containsOnly(rule2.getKey());

    // empty list => no filter
    query = new RuleQuery().setTags(Collections.emptySet());
    assertThat(index.search(query, new SearchOptions()).getIds()).containsOnly(rule1.getKey(), rule2.getKey());

    // null list => no filter
    query = new RuleQuery().setTags(null);
    assertThat(index.search(query, new SearchOptions()).getIds()).containsOnly(rule1.getKey(), rule2.getKey());
  }

  @SafeVarargs
  private final RuleDto createRule(Consumer<RuleDto>... populaters) {
    RuleDto ruleDto = dbTester.rules().insertRule(dbTester.getDefaultOrganization(), populaters);
    dbTester.getSession().commit();
    ruleIndexer.indexRuleDefinitions(asList(ruleDto.getDefinition().getKey()));
    return ruleDto;
  }

  @Test
  public void tags_facet_supports_selected_value_with_regexp_special_characters() {
    createRule(rule -> rule.setTags(singleton("misra++")));

    RuleQuery query = new RuleQuery().setTags(singletonList("misra["));
    SearchOptions options = new SearchOptions().addFacets(RuleIndex.FACET_TAGS);

    // do not fail
    assertThat(index.search(query, options).getTotal()).isEqualTo(0);
  }

  @Test
  public void search_by_types() {
    RuleDto codeSmell = createRule(rule -> rule.setType(CODE_SMELL));
    RuleDto vulnerability = createRule(rule -> rule.setType(VULNERABILITY));
    RuleDto bug1 = createRule(rule -> rule.setType(BUG));
    RuleDto bug2 = createRule(rule -> rule.setType(BUG));

    // find all
    RuleQuery query = new RuleQuery();
    assertThat(index.search(query, new SearchOptions()).getIds()).hasSize(4);

    // type3 in filter
    query = new RuleQuery().setTypes(of(VULNERABILITY));
    assertThat(index.search(query, new SearchOptions()).getIds()).containsOnly(vulnerability.getKey());

    query = new RuleQuery().setTypes(of(BUG));
    assertThat(index.search(query, new SearchOptions()).getIds()).containsOnly(bug1.getKey(), bug2.getKey());

    // types in query => nothing
    query = new RuleQuery().setQueryText("code smell bug vulnerability");
    assertThat(index.search(query, new SearchOptions()).getIds()).isEmpty();

    // null list => no filter
    query = new RuleQuery().setTypes(Collections.emptySet());
    assertThat(index.search(query, new SearchOptions()).getIds()).hasSize(4);

    // null list => no filter
    query = new RuleQuery().setTypes(null);
    assertThat(index.search(query, new SearchOptions()).getIds()).hasSize(4);
  }

  @Test
  public void search_by_is_template() {
    RuleDto ruleNoTemplate = createRule(rule -> rule.setIsTemplate(false));
    RuleDto ruleIsTemplate = createRule(rule -> rule.setIsTemplate(true));

    // find all
    RuleQuery query = new RuleQuery();
    SearchIdResult results = index.search(query, new SearchOptions());
    assertThat(results.getIds()).hasSize(2);

    // Only template
    query = new RuleQuery().setIsTemplate(true);
    results = index.search(query, new SearchOptions());
    assertThat(results.getIds()).containsOnly(ruleIsTemplate.getKey());

    // Only not template
    query = new RuleQuery().setIsTemplate(false);
    results = index.search(query, new SearchOptions());
    assertThat(results.getIds()).containsOnly(ruleNoTemplate.getKey());

    // null => no filter
    query = new RuleQuery().setIsTemplate(null);
    results = index.search(query, new SearchOptions());
    assertThat(results.getIds()).containsOnly(ruleIsTemplate.getKey(), ruleNoTemplate.getKey());
  }

  @Test
  public void search_by_template_key() {
    RuleDto template = createRule(rule -> rule.setIsTemplate(true));
    RuleDto customRule = createRule(rule -> rule.setTemplateId(template.getId()));

    // find all
    RuleQuery query = new RuleQuery();
    SearchIdResult results = index.search(query, new SearchOptions());
    assertThat(results.getIds()).hasSize(2);

    // Only custom rule
    query = new RuleQuery().setTemplateKey(template.getKey().toString());
    results = index.search(query, new SearchOptions());
    assertThat(results.getIds()).containsOnly(customRule.getKey());

    // null => no filter
    query = new RuleQuery().setTemplateKey(null);
    assertThat(index.search(query, new SearchOptions()).getIds()).hasSize(2);
  }

  @Test
  public void search_by_any_of_languages() {
    RuleDto java = createRule(rule -> rule.setLanguage("java"));
    RuleDto javascript = createRule(rule -> rule.setLanguage("js"));

    RuleQuery query = new RuleQuery().setLanguages(asList("cobol", "js"));
    SearchIdResult results = index.search(query, new SearchOptions());
    assertThat(results.getIds()).containsOnly(javascript.getKey());

    // no results
    query = new RuleQuery().setLanguages(singletonList("cpp"));
    assertThat(index.search(query, new SearchOptions()).getIds()).isEmpty();

    // empty list => no filter
    query = new RuleQuery().setLanguages(Collections.emptyList());
    assertThat(index.search(query, new SearchOptions()).getIds()).hasSize(2);

    // null list => no filter
    query = new RuleQuery().setLanguages(null);
    assertThat(index.search(query, new SearchOptions()).getIds()).hasSize(2);
  }

  @Test
  public void search_by_any_of_severities() {
    RuleDto blocker = createRule(rule -> rule.setSeverity(BLOCKER));
    RuleDto info = createRule(rule -> rule.setSeverity(INFO));

    RuleQuery query = new RuleQuery().setSeverities(asList(INFO, MINOR));
    SearchIdResult results = index.search(query, new SearchOptions());
    assertThat(results.getIds()).containsOnly(info.getKey());

    // no results
    query = new RuleQuery().setSeverities(singletonList(MINOR));
    assertThat(index.search(query, new SearchOptions()).getIds()).isEmpty();

    // empty list => no filter
    query = new RuleQuery().setSeverities(Collections.emptyList());
    assertThat(index.search(query, new SearchOptions()).getIds()).hasSize(2);

    // null list => no filter
    query = new RuleQuery().setSeverities();
    assertThat(index.search(query, new SearchOptions()).getIds()).hasSize(2);
  }

  @Test
  public void search_by_any_of_statuses() {
    RuleDto beta = createRule(rule -> rule.setStatus(RuleStatus.BETA));
    RuleDto ready = createRule(rule -> rule.setStatus(RuleStatus.READY));

    RuleQuery query = new RuleQuery().setStatuses(asList(RuleStatus.DEPRECATED, RuleStatus.READY));
    SearchIdResult<RuleKey> results = index.search(query, new SearchOptions());
    assertThat(results.getIds()).containsOnly(ready.getKey());

    // no results
    query = new RuleQuery().setStatuses(singletonList(RuleStatus.DEPRECATED));
    assertThat(index.search(query, new SearchOptions()).getIds()).isEmpty();

    // empty list => no filter
    query = new RuleQuery().setStatuses(Collections.emptyList());
    assertThat(index.search(query, new SearchOptions()).getIds()).hasSize(2);

    // null list => no filter
    query = new RuleQuery().setStatuses(null);
    assertThat(index.search(query, new SearchOptions()).getIds()).hasSize(2);
  }

  @Test
  public void search_by_profile() throws InterruptedException {
    RuleDto rule1 = createRule();
    RuleDto rule2 = createRule();
    RuleDto rule3 = createRule();

    indexActiveRules(
      ActiveRuleDocTesting.newDoc(ActiveRuleKey.of(QUALITY_PROFILE_KEY1, rule1.getKey())),
      ActiveRuleDocTesting.newDoc(ActiveRuleKey.of(QUALITY_PROFILE_KEY2, rule1.getKey())),
      ActiveRuleDocTesting.newDoc(ActiveRuleKey.of(QUALITY_PROFILE_KEY1, rule2.getKey())));

    assertThat(tester.countDocuments(INDEX_TYPE_ACTIVE_RULE)).isEqualTo(3);

    // 1. get all active rules.
    assertThat(index.search(new RuleQuery().setActivation(true), new SearchOptions()).getIds())
      .containsOnly(rule1.getKey(), rule2.getKey());

    // 2. get all inactive rules.
    assertThat(index.search(new RuleQuery().setActivation(false), new SearchOptions()).getIds())
      .containsOnly(rule3.getKey());

    // 3. get all rules not active on profile
    assertThat(index.search(new RuleQuery().setActivation(false).setQProfileKey(QUALITY_PROFILE_KEY2), new SearchOptions()).getIds())
      .containsOnly(rule2.getKey(), rule3.getKey());

    // 4. get all active rules on profile
    assertThat(index.search(new RuleQuery().setActivation(true).setQProfileKey(QUALITY_PROFILE_KEY2), new SearchOptions()).getIds())
      .containsOnly(rule1.getKey());
  }

  @Test
  public void search_by_profile_and_inheritance() {
    RuleDto rule1 = createRule();
    RuleDto rule2 = createRule();
    RuleDto rule3 = createRule();
    RuleDto rule4 = createRule();

    ActiveRuleKey activeRuleKey1 = ActiveRuleKey.of(QUALITY_PROFILE_KEY1, rule1.getKey());
    ActiveRuleKey activeRuleKey2 = ActiveRuleKey.of(QUALITY_PROFILE_KEY1, rule2.getKey());
    ActiveRuleKey activeRuleKey3 = ActiveRuleKey.of(QUALITY_PROFILE_KEY1, rule3.getKey());

    indexActiveRules(
      ActiveRuleDocTesting.newDoc(activeRuleKey1),
      ActiveRuleDocTesting.newDoc(activeRuleKey2),
      ActiveRuleDocTesting.newDoc(activeRuleKey3),
      // Profile 2 is a child a profile 1
      ActiveRuleDocTesting.newDoc(ActiveRuleKey.of(QUALITY_PROFILE_KEY2, rule1.getKey())).setInheritance(INHERITED.name()),
      ActiveRuleDocTesting.newDoc(ActiveRuleKey.of(QUALITY_PROFILE_KEY2, rule2.getKey())).setInheritance(OVERRIDES.name()),
      ActiveRuleDocTesting.newDoc(ActiveRuleKey.of(QUALITY_PROFILE_KEY2, rule3.getKey())).setInheritance(INHERITED.name()));

    // 0. get all rules
    assertThat(index.search(new RuleQuery(), new SearchOptions()).getIds())
      .hasSize(4);

    // 1. get all active rules
    assertThat(index.search(new RuleQuery()
      .setActivation(true), new SearchOptions()).getIds())
        .hasSize(3);

    // 2. get all inactive rules.
    assertThat(index.search(new RuleQuery()
      .setActivation(false), new SearchOptions()).getIds())
        .containsOnly(rule4.getKey());

    // 3. get Inherited Rules on profile1
    assertThat(index.search(new RuleQuery().setActivation(true)
      .setQProfileKey(QUALITY_PROFILE_KEY1)
      .setInheritance(of(INHERITED.name())),
      new SearchOptions()).getIds())
        .isEmpty();

    // 4. get Inherited Rules on profile2
    assertThat(index.search(new RuleQuery().setActivation(true)
      .setQProfileKey(QUALITY_PROFILE_KEY2)
      .setInheritance(of(INHERITED.name())),
      new SearchOptions()).getIds())
        .hasSize(2);

    // 5. get Overridden Rules on profile1
    assertThat(index.search(new RuleQuery().setActivation(true)
      .setQProfileKey(QUALITY_PROFILE_KEY1)
      .setInheritance(of(OVERRIDES.name())),
      new SearchOptions()).getIds())
        .isEmpty();

    // 6. get Overridden Rules on profile2
    assertThat(index.search(new RuleQuery().setActivation(true)
      .setQProfileKey(QUALITY_PROFILE_KEY2)
      .setInheritance(of(OVERRIDES.name())),
      new SearchOptions()).getIds())
        .hasSize(1);

    // 7. get Inherited AND Overridden Rules on profile1
    assertThat(index.search(new RuleQuery().setActivation(true)
      .setQProfileKey(QUALITY_PROFILE_KEY1)
      .setInheritance(of(INHERITED.name(), OVERRIDES.name())),
      new SearchOptions()).getIds())
        .isEmpty();

    // 8. get Inherited AND Overridden Rules on profile2
    assertThat(index.search(new RuleQuery().setActivation(true)
      .setQProfileKey(QUALITY_PROFILE_KEY2)
      .setInheritance(of(INHERITED.name(), OVERRIDES.name())),
      new SearchOptions()).getIds())
        .hasSize(3);
  }

  @Test
  public void search_by_profile_and_active_severity() {
    RuleDto major = createRule(rule -> rule.setSeverity(MAJOR));
    RuleDto minor = createRule(rule -> rule.setSeverity(MINOR));
    RuleDto info = createRule(rule -> rule.setSeverity(INFO));

    indexActiveRules(
      ActiveRuleDocTesting.newDoc(ActiveRuleKey.of(QUALITY_PROFILE_KEY1, major.getKey())).setSeverity(BLOCKER),
      ActiveRuleDocTesting.newDoc(ActiveRuleKey.of(QUALITY_PROFILE_KEY2, major.getKey())).setSeverity(BLOCKER),
      ActiveRuleDocTesting.newDoc(ActiveRuleKey.of(QUALITY_PROFILE_KEY1, minor.getKey())).setSeverity(CRITICAL));

    // 1. get all active rules.
    assertThat(index.search(new RuleQuery().setActivation(true).setQProfileKey(QUALITY_PROFILE_KEY1), new SearchOptions()).getIds())
      .hasSize(2);

    // 2. get rules with active severity critical.
    SearchIdResult<RuleKey> result = index.search(new RuleQuery().setActivation(true)
      .setQProfileKey(QUALITY_PROFILE_KEY1).setActiveSeverities(singletonList(CRITICAL)),
      new SearchOptions().addFacets(singletonList(RuleIndex.FACET_ACTIVE_SEVERITIES)));
    assertThat(result.getIds()).containsOnly(minor.getKey());

    // check stickyness of active severity facet
    assertThat(result.getFacets().get(RuleIndex.FACET_ACTIVE_SEVERITIES)).containsOnly(entry(BLOCKER, 1L), entry(CRITICAL, 1L));

    // 3. count activation severities of all active rules
    result = index.search(new RuleQuery(), new SearchOptions().addFacets(singletonList(RuleIndex.FACET_ACTIVE_SEVERITIES)));
    assertThat(result.getIds()).hasSize(3);
    assertThat(result.getFacets().get(RuleIndex.FACET_ACTIVE_SEVERITIES)).containsOnly(entry(BLOCKER, 2L), entry(CRITICAL, 1L));
  }

  @Test
  public void all_tags() {
    OrganizationDto organization = dbTester.organizations().insert();

    RuleDto rule1 = createRule(
      rule -> rule.setOrganizationUuid(organization.getUuid()),
      rule -> rule.setTags(of("tag1")),
      rule -> rule.setSystemTags(of("sys1", "sys2")));
    ruleIndexer.indexRuleDefinitions(asList(rule1.getKey()));
    ruleIndexer.indexRuleExtension(organization, rule1.getKey());
    RuleDto rule2 = createRule(
      rule -> rule.setTags(of("tag2")),
      rule -> rule.setSystemTags(of()));
    ruleIndexer.indexRuleDefinitions(asList(rule2.getKey()));
    ruleIndexer.indexRuleExtension(organization, rule2.getKey());

    assertThat(index.listTags(organization.getUuid(), null, 10)).containsOnly("tag1", "tag2", "sys1", "sys2");
  }

  @Test
  public void all_tags_minds_the_oranization() {
    OrganizationDto organization1 = dbTester.organizations().insert();
    OrganizationDto organization2 = dbTester.organizations().insert();

    RuleDto rule1 = createRule(
      rule -> rule.setTags(of("tag1")),
      rule -> rule.setSystemTags(of("sys1")));
    ruleIndexer.indexRuleDefinitions(asList(rule1.getKey()));
    ruleIndexer.indexRuleExtension(organization1, rule1.getKey());
    RuleDto rule2 = createRule(
      rule -> rule.setTags(of("tag2")),
      rule -> rule.setSystemTags(of("sys2")));
    ruleIndexer.indexRuleDefinitions(asList(rule2.getKey()));
    ruleIndexer.indexRuleExtension(organization2, rule2.getKey());

    assertThat(index.listTags(organization1.getUuid(), null, 10)).containsOnly("tag1", "sys1", "sys2");
    assertThat(index.listTags(organization2.getUuid(), null, 10)).containsOnly("tag2", "sys1", "sys2");
  }

  @Test
  public void available_since() throws InterruptedException {
    RuleDto rule1 = createRule(
      rule -> rule.setCreatedAt(1000L));
    RuleDto rule2 = createRule(
      rule -> rule.setCreatedAt(2000L));

    // 0. find all rules;
    assertThat(index.search(new RuleQuery(), new SearchOptions()).getIds()).containsOnly(rule1.getKey(), rule2.getKey());

    // 1. find all rules available since a date;
    RuleQuery availableSinceQuery = new RuleQuery().setAvailableSince(2000L);
    assertThat(index.search(availableSinceQuery, new SearchOptions()).getIds()).containsOnly(rule2.getKey());

    // 2. find no new rules since tomorrow.
    RuleQuery availableSinceNowQuery = new RuleQuery().setAvailableSince(3000L);
    assertThat(index.search(availableSinceNowQuery, new SearchOptions()).getIds()).containsOnly();
  }

  @Test
  public void global_facet_on_repositories_and_tags() {
    createRule(
      rule -> rule.setRepositoryKey("php"),
      rule -> rule.setSystemTags(of("sysTag")));
    createRule(
      rule -> rule.setRepositoryKey("php"),
      rule -> rule.setTags(of("tag1")));
    createRule(
      rule -> rule.setRepositoryKey("javascript"),
      rule -> rule.setTags(of("tag1", "tag2")));

    // should not have any facet!
    RuleQuery query = new RuleQuery();
    SearchIdResult result = index.search(query, new SearchOptions());
    assertThat(result.getFacets().getAll()).isEmpty();

    // should not have any facet on non matching query!
    result = index.search(new RuleQuery().setQueryText("aeiou"), new SearchOptions().addFacets(singletonList("repositories")));
    assertThat(result.getFacets().getAll()).hasSize(1);
    assertThat(result.getFacets().getAll().get("repositories")).isEmpty();

    // Repositories Facet is preset
    result = index.search(query, new SearchOptions().addFacets(asList("repositories", "tags")));
    assertThat(result.getFacets()).isNotNull();
    assertThat(result.getFacets().getAll()).hasSize(2);

    // Verify the value of a given facet
    Map<String, Long> repoFacets = result.getFacets().get("repositories");
    assertThat(repoFacets).containsOnly(entry("php", 2L), entry("javascript", 1L));

    // Check that tag facet has both Tags and SystemTags values
    Map<String, Long> tagFacets = result.getFacets().get("tags");
    assertThat(tagFacets).containsOnly(entry("tag1", 2L), entry("sysTag", 1L), entry("tag2", 1L));
  }

/*
  @Test
  public void sticky_facets() {
    indexRules(
      newDoc(RuleKey.of("xoo", "S001")).setLanguage("java").setAllTags(Collections.emptyList()).setType(BUG),
      newDoc(RuleKey.of("xoo", "S002")).setLanguage("java").setAllTags(Collections.emptyList()).setType(CODE_SMELL),
      newDoc(RuleKey.of("xoo", "S003")).setLanguage("java").setAllTags(asList("T1", "T2")).setType(CODE_SMELL),
      newDoc(RuleKey.of("xoo", "S011")).setLanguage("cobol").setAllTags(Collections.emptyList()).setType(CODE_SMELL),
      newDoc(RuleKey.of("xoo", "S012")).setLanguage("cobol").setAllTags(Collections.emptyList()).setType(BUG),
      newDoc(RuleKey.of("foo", "S013")).setLanguage("cobol").setAllTags(asList("T3", "T4")).setType(VULNERABILITY),
      newDoc(RuleKey.of("foo", "S111")).setLanguage("cpp").setAllTags(Collections.emptyList()).setType(BUG),
      newDoc(RuleKey.of("foo", "S112")).setLanguage("cpp").setAllTags(Collections.emptyList()).setType(CODE_SMELL),
      newDoc(RuleKey.of("foo", "S113")).setLanguage("cpp").setAllTags(asList("T2", "T3")).setType(CODE_SMELL));

    // 0 assert Base
    assertThat(index.search(new RuleQuery(), new SearchOptions()).getIds()).hasSize(9);

    // 1 Facet with no filters at all
    SearchIdResult result = index.search(new RuleQuery(), new SearchOptions().addFacets(asList(FACET_LANGUAGES, FACET_REPOSITORIES, FACET_TAGS, FACET_TYPES)));
    assertThat(result.getFacets().getAll()).hasSize(4);
    assertThat(result.getFacets().getAll().get(FACET_LANGUAGES).keySet()).containsOnly("cpp", "java", "cobol");
    assertThat(result.getFacets().getAll().get(FACET_REPOSITORIES).keySet()).containsExactly("xoo", "foo");
    assertThat(result.getFacets().getAll().get(FACET_TAGS).keySet()).containsOnly("T1", "T2", "T3", "T4");
    assertThat(result.getFacets().getAll().get(FACET_TYPES).keySet()).containsOnly("BUG", "CODE_SMELL", "VULNERABILITY");

    // 2 Facet with a language filter
    // -- lang facet should still have all language
    result = index.search(new RuleQuery().setLanguages(ImmutableList.of("cpp")), new SearchOptions().addFacets(asList(FACET_LANGUAGES, FACET_REPOSITORIES, FACET_TAGS)));
    assertThat(result.getIds()).hasSize(3);
    assertThat(result.getFacets().getAll()).hasSize(3);
    assertThat(result.getFacets().get(FACET_LANGUAGES).keySet()).containsOnly("cpp", "java", "cobol");

    // 3 facet with 2 filters
    // -- lang facet for tag T2
    // -- tag facet for lang cpp
    // -- repository for cpp & T2
    result = index.search(new RuleQuery()
      .setLanguages(ImmutableList.of("cpp"))
      .setTags(ImmutableList.of("T2")), new SearchOptions().addFacets(asList(FACET_LANGUAGES, FACET_REPOSITORIES, FACET_TAGS)));
    assertThat(result.getIds()).hasSize(1);
    assertThat(result.getFacets().getAll()).hasSize(3);
    assertThat(result.getFacets().get(FACET_LANGUAGES).keySet()).containsOnly("cpp", "java");
    assertThat(result.getFacets().get(FACET_REPOSITORIES).keySet()).containsOnly("foo");
    assertThat(result.getFacets().get(FACET_TAGS).keySet()).containsOnly("T2", "T3");

    // 4 facet with 3 filters
    // -- lang facet for tag T2
    // -- tag facet for lang cpp & java
    // -- repository for (cpp || java) & T2
    // -- type
    result = index.search(new RuleQuery()
      .setLanguages(ImmutableList.of("cpp", "java"))
      .setTags(ImmutableList.of("T2"))
      .setTypes(asList(BUG, CODE_SMELL)), new SearchOptions().addFacets(asList(FACET_LANGUAGES, FACET_REPOSITORIES, FACET_TAGS, FACET_TYPES)));
    assertThat(result.getIds()).hasSize(2);
    assertThat(result.getFacets().getAll()).hasSize(4);
    assertThat(result.getFacets().get(FACET_LANGUAGES).keySet()).containsOnly("cpp", "java");
    assertThat(result.getFacets().get(FACET_REPOSITORIES).keySet()).containsOnly("foo", "xoo");
    assertThat(result.getFacets().get(FACET_TAGS).keySet()).containsOnly("T1", "T2", "T3");
    assertThat(result.getFacets().get(FACET_TYPES).keySet()).containsOnly("CODE_SMELL");
  }
*/

/*
  @Test
  public void sort_by_name() {
    indexRules(
      newDoc(RuleKey.of("java", "S001")).setName("abcd"),
      newDoc(RuleKey.of("java", "S002")).setName("ABC"),
      newDoc(RuleKey.of("java", "S003")).setName("FGH"));

    // ascending
    RuleQuery query = new RuleQuery().setSortField(RuleIndexDefinition.FIELD_RULE_NAME);
    SearchIdResult<RuleKey> results = index.search(query, new SearchOptions());
    assertThat(results.getIds()).containsExactly(RuleKey.of("java", "S002"), RuleKey.of("java", "S001"), RuleKey.of("java", "S003"));

    // descending
    query = new RuleQuery().setSortField(RuleIndexDefinition.FIELD_RULE_NAME).setAscendingSort(false);
    results = index.search(query, new SearchOptions());
    assertThat(results.getIds()).containsExactly(RuleKey.of("java", "S003"), RuleKey.of("java", "S001"), RuleKey.of("java", "S002"));
  }

  @Test
  public void default_sort_is_by_updated_at_desc() {
    indexRules(
      newDoc(RuleKey.of("java", "S001")).setCreatedAt(1000L).setUpdatedAt(1000L),
      newDoc(RuleKey.of("java", "S002")).setCreatedAt(1000L).setUpdatedAt(3000L),
      newDoc(RuleKey.of("java", "S003")).setCreatedAt(1000L).setUpdatedAt(2000L));

    SearchIdResult<RuleKey> results = index.search(new RuleQuery(), new SearchOptions());
    assertThat(results.getIds()).containsExactly(RuleKey.of("java", "S002"), RuleKey.of("java", "S003"), RuleKey.of("java", "S001"));
  }
*/

  @Test
  public void fail_sort_by_language() {
    try {
      // Sorting on a field not tagged as sortable
      new RuleQuery().setSortField(RuleIndexDefinition.FIELD_RULE_LANGUAGE);
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessage("Field 'lang' is not sortable");
    }
  }

/*  @Test
  public void paging() {
    indexRules(
      newDoc(RuleKey.of("java", "S001")),
      newDoc(RuleKey.of("java", "S002")),
      newDoc(RuleKey.of("java", "S003")));

    // from 0 to 1 included
    SearchOptions options = new SearchOptions();
    options.setOffset(0).setLimit(2);
    SearchIdResult results = index.search(new RuleQuery(), options);
    assertThat(results.getTotal()).isEqualTo(3);
    assertThat(results.getIds()).hasSize(2);

    // from 0 to 9 included
    options.setOffset(0).setLimit(10);
    results = index.search(new RuleQuery(), options);
    assertThat(results.getTotal()).isEqualTo(3);
    assertThat(results.getIds()).hasSize(3);

    // from 2 to 11 included
    options.setOffset(2).setLimit(10);
    results = index.search(new RuleQuery(), options);
    assertThat(results.getTotal()).isEqualTo(3);
    assertThat(results.getIds()).hasSize(1);

    // from 2 to 11 included
    options.setOffset(2).setLimit(0);
    results = index.search(new RuleQuery(), options);
    assertThat(results.getTotal()).isEqualTo(3);
    assertThat(results.getIds()).hasSize(1);
  }

  @Test
  public void search_all_keys_by_query() {
    indexRules(
      newDoc(RuleKey.of("javascript", "X001")),
      newDoc(RuleKey.of("cobol", "X001")),
      newDoc(RuleKey.of("php", "S002")));

    // key
    assertThat(index.searchAll(new RuleQuery().setQueryText("X001"))).hasSize(2);

    // partial key does not match
    assertThat(index.searchAll(new RuleQuery().setQueryText("X00"))).isEmpty();

    // repo:key -> nice-to-have !
    assertThat(index.searchAll(new RuleQuery().setQueryText("javascript:X001"))).hasSize(1);
  }

  @Test
  public void search_all_keys_by_profile() {
    indexRules(
      newDoc(RULE_KEY_1),
      newDoc(RULE_KEY_2),
      newDoc(RULE_KEY_3));

    indexActiveRules(
      ActiveRuleDocTesting.newDoc(ActiveRuleKey.of(QUALITY_PROFILE_KEY1, RULE_KEY_1)),
      ActiveRuleDocTesting.newDoc(ActiveRuleKey.of(QUALITY_PROFILE_KEY2, RULE_KEY_1)),
      ActiveRuleDocTesting.newDoc(ActiveRuleKey.of(QUALITY_PROFILE_KEY1, RULE_KEY_2)));

    assertThat(tester.countDocuments(INDEX_TYPE_ACTIVE_RULE)).isEqualTo(3);

    // 1. get all active rules.
    assertThat(index.searchAll(new RuleQuery().setActivation(true))).containsOnly(RULE_KEY_1, RULE_KEY_2);

    // 2. get all inactive rules.
    assertThat(index.searchAll(new RuleQuery().setActivation(false))).containsOnly(RULE_KEY_3);

    // 3. get all rules not active on profile
    assertThat(index.searchAll(new RuleQuery().setActivation(false).setQProfileKey(QUALITY_PROFILE_KEY2))).containsOnly(RULE_KEY_2, RULE_KEY_3);

    // 4. get all active rules on profile
    assertThat(index.searchAll(new RuleQuery().setActivation(true).setQProfileKey(QUALITY_PROFILE_KEY2))).containsOnly(RULE_KEY_1);
  }*/

  private void indexActiveRules(ActiveRuleDoc... docs) {
    activeRuleIndexer.index(asList(docs).iterator());
  }
}
