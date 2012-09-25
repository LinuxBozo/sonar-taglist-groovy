/*
 * Sonar, open source software quality management tool.
 * Copyright (C) 2009 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * Sonar is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Sonar is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Sonar; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */

package org.sonar.plugins.taglist.groovy;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.any;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.doubleThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.anyString;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import org.junit.Before;
import org.junit.Test;
import org.sonar.api.CoreProperties;
import org.sonar.api.batch.DecoratorContext;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.resources.Java;
import org.sonar.api.resources.File;
import org.sonar.api.resources.Language;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Resource;
import org.sonar.api.rules.Rule;
import org.sonar.api.rules.RuleFinder;
import org.sonar.api.rules.RulePriority;
import org.sonar.api.rules.RuleQuery;
import org.sonar.api.rules.Violation;
import org.sonar.api.measures.Metric;
import org.sonar.api.measures.MetricFinder;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.test.IsMeasure;


public class ViolationsDecoratorGroovyTest {
    
  private static final String GROOVY_LANGUAGE_KEY = "grvy";

  private ViolationsDecoratorGroovy decorator;
  private RulesProfile rulesProfile = RulesProfile.create();
  private Rule rule1, rule2, rule3;
  private RuleFinder ruleFinder;
  private MetricFinder metricFinder;
  private DecoratorContext context;
  private File javaFile;
  private TaglistMetrics taglistMetrics;

  private static final Metric TAGS = new Metric(
         "tags", "Tags", "Number of tags in the source code",
         Metric.ValueType.INT, Metric.DIRECTION_WORST, true, CoreMetrics.DOMAIN_RULES);
  private static final Metric MANDATORY_TAGS = new Metric(
         "mandatory_tags", "Mandatory tags", "Number of mandatory tags in the source code",
         Metric.ValueType.INT, Metric.DIRECTION_WORST, true, CoreMetrics.DOMAIN_RULES);
  private static final Metric OPTIONAL_TAGS = new Metric(
         "optional_tags", "Optional tags", "Number of optional tags in the source code",
         Metric.ValueType.INT, Metric.DIRECTION_WORST, true, CoreMetrics.DOMAIN_RULES);
  private static final Metric TAGS_DISTRIBUTION = new Metric(
         "tags_distribution", "Tags distribution", "Distribution of tags in the source code",
         Metric.ValueType.DISTRIB, Metric.DIRECTION_NONE, false, CoreMetrics.DOMAIN_RULES);
  private static final Metric NOSONAR_TAGS = new Metric(
         "nosonar_tags", "NOSONAR tags", "Number of NOSONAR tags in the source code",
         Metric.ValueType.INT, Metric.DIRECTION_WORST, true, CoreMetrics.DOMAIN_RULES);

  @Before
  public void setUp() {
    rulesProfile = RulesProfile.create();
    rule1 = createCodeNarcRule().setKey("key1");
    rule2 = createCodeNarcRule().setKey("key2");
    rule3 = createSquidRule();
    Rule inactiveRule = createCodeNarcRule().setKey("key3");
    rulesProfile.activateRule(rule1, RulePriority.BLOCKER).setParameter("regex", "FIXME");
    rulesProfile.activateRule(rule2, RulePriority.MAJOR).setParameter("regex", "TODO");
    rulesProfile.activateRule(rule3, RulePriority.INFO);
    
    ruleFinder = mock(RuleFinder.class);
    when(ruleFinder.findAll(argThat(any(RuleQuery.class)))).thenReturn(Arrays.asList(rule1, rule2, inactiveRule, rule3));
    
    metricFinder = mock(MetricFinder.class);
    when(metricFinder.findByKey(eq("tags"))).thenReturn(TAGS);
    when(metricFinder.findByKey(eq("optional_tags"))).thenReturn(OPTIONAL_TAGS);
    when(metricFinder.findByKey(eq("mandatory_tags"))).thenReturn(MANDATORY_TAGS);
    when(metricFinder.findByKey(eq("nosonar_tags"))).thenReturn(NOSONAR_TAGS);
    when(metricFinder.findByKey(eq("tags_distribution"))).thenReturn(TAGS_DISTRIBUTION);

    context = mock(DecoratorContext.class);
    javaFile = new File("org.example", "HelloWorld");

    taglistMetrics = new TaglistMetrics(metricFinder);
    decorator = new ViolationsDecoratorGroovy(rulesProfile, ruleFinder, metricFinder);
  }

  @Test
  public void dependedUpon() {
    assertThat(decorator.dependedUpon().size(), is(5));
  }

  @Test
  public void shouldExecuteOnlyOnGroovyProject() {
    Project project = mock(Project.class);
    when(project.getLanguageKey()).thenReturn(GROOVY_LANGUAGE_KEY).thenReturn("java");

    assertThat(decorator.shouldExecuteOnProject(project), is(true));
    assertThat(decorator.shouldExecuteOnProject(project), is(false));
  }

  @Test
  public void dontDecoratePackage() {
    Resource resource = mock(Resource.class);
    when(resource.getQualifier()).thenReturn(Resource.QUALIFIER_PACKAGE);
    ViolationsDecoratorGroovy spy = spy(decorator);

    spy.decorate(resource, context);

    verify(spy, never()).saveFileMeasures(eq(context), argThat(any(Collection.class)));
  }

  @Test
  public void shouldSaveMetrics() {
    Violation mandatory = Violation.create(rule1, null);
    Violation optional = Violation.create(rule2, null);
    Violation info = Violation.create(rule3, null);
    when(context.getViolations()).thenReturn(Arrays.asList(mandatory, optional, info));

    decorator.decorate(javaFile, context);

    verify(context, atLeastOnce()).getViolations();
    verify(context).saveMeasure(eq(taglistMetrics.getTags()), doubleThat(equalTo(3.0)));
    verify(context).saveMeasure(eq(taglistMetrics.getMandatoryTags()), doubleThat(equalTo(1.0)));
    verify(context).saveMeasure(eq(taglistMetrics.getOptionalTags()), doubleThat(equalTo(2.0)));
    verify(context).saveMeasure(eq(taglistMetrics.getNosonarTags()), doubleThat(equalTo(1.0)));
    verify(context).saveMeasure(argThat(new IsMeasure(taglistMetrics.getTagsDistribution(), "FIXME=1;NOSONAR=1;TODO=1")));
    verifyNoMoreInteractions(context);
  }

  @Test
  public void shouldntSaveMetricsIfNoTags() {
    when(context.getViolations()).thenReturn(Collections.<Violation> emptyList());

    decorator.decorate(javaFile, context);

    verify(context, atLeastOnce()).getViolations();
    verifyNoMoreInteractions(context);
  }

  @Test
  public void rulePriorities() {
    assertThat(ViolationsDecoratorGroovy.isMandatory(RulePriority.BLOCKER), is(true));
    assertThat(ViolationsDecoratorGroovy.isMandatory(RulePriority.CRITICAL), is(true));
    assertThat(ViolationsDecoratorGroovy.isMandatory(RulePriority.MAJOR), is(false));
    assertThat(ViolationsDecoratorGroovy.isMandatory(RulePriority.MINOR), is(false));
    assertThat(ViolationsDecoratorGroovy.isMandatory(RulePriority.INFO), is(false));
  }

  private Rule createSquidRule() {
    Rule rule = Rule.create();
    rule.setRepositoryKey(CoreProperties.SQUID_PLUGIN);
    return rule;
  }

  private Rule createCodeNarcRule() {
    Rule rule = Rule.create();
    rule.setRepositoryKey(GROOVY_LANGUAGE_KEY);
    rule.createParameter("regex").setDefaultValue("TODO:");
    return rule;
  }

}
