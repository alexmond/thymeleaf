/*
 * =============================================================================
 *
 *   Copyright (c) 2011-2026 Thymeleaf (http://www.thymeleaf.org)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 * =============================================================================
 */
package org.thymeleaf.expression;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templateresolver.StringTemplateResolver;

/*
 * SpEL caches the property accessor used at each property reference of a parsed expression, and Thymeleaf
 * caches parsed expressions. These tests evaluate the same expression several times against targets of
 * different types, checking that a cached accessor never skips the member access restrictions and never
 * changes which accessor handles a given type.
 */
public class ExpressionPropertyAccessorCacheTest {

    private static final StaticApplicationContext APPLICATION_CONTEXT = new StaticApplicationContext();

    static {
        APPLICATION_CONTEXT.refresh();
    }


    public static class Harmless {
        public String getContextClassLoader() {
            return "harmless";
        }
        public int getSize() {
            return 42;
        }
        public String getName() {
            return "bean";
        }
    }

    public static class OtherHarmless {
        public String getName() {
            return "other";
        }
    }


    private static String process(final SpringTemplateEngine templateEngine, final String template,
                                  final Map<String,Object> variables, final Collection<Class<?>> allowedClassOverrides) {
        final Context context = new Context(Locale.US);
        context.setVariables(variables);
        // We simulate what ThymeleafView does by putting a new ThymeleafEvaluationContext in the model for each request
        final ThymeleafEvaluationContext evaluationContext =
                new ThymeleafEvaluationContext(APPLICATION_CONTEXT, null, allowedClassOverrides);
        context.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, evaluationContext);
        return templateEngine.process(template, context);
    }

    private static SpringTemplateEngine templateEngine() {
        final SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(new StringTemplateResolver());
        return templateEngine;
    }


    @Test
    public void testForbiddenMemberOfDirectTarget() {
        // Sanity check: reading a property of a forbidden type is rejected when nothing has been cached
        final Map<String,Object> variables = new HashMap<>();
        variables.put("danger", Thread.currentThread());
        Assertions.assertThrows(Exception.class, () ->
                process(templateEngine(), "<p th:text=\"${danger.contextClassLoader}\"></p>", variables, null));
    }


    @Test
    public void testCachedPropertyAccessStillChecksForbiddenMembers() {
        checkCachedPropertyAccessStillChecksForbiddenMembers(null);
    }


    @Test
    public void testCachedPropertyAccessStillChecksForbiddenMembersWithAllowedClassOverrides() {
        // With allowed class overrides, each evaluation context creates its own property accessor
        checkCachedPropertyAccessStillChecksForbiddenMembers(Collections.singleton(Harmless.class));
    }


    private static void checkCachedPropertyAccessStillChecksForbiddenMembers(final Collection<Class<?>> allowedClassOverrides) {

        final SpringTemplateEngine templateEngine = templateEngine();
        final String template = "<p th:text=\"${(useDanger ? danger : harmless).contextClassLoader}\"></p>";

        final Map<String,Object> variables = new HashMap<>();
        variables.put("harmless", new Harmless());
        variables.put("danger", Thread.currentThread());

        // Evaluate several times on the allowed type so that the property accessor gets cached...
        variables.put("useDanger", Boolean.FALSE);
        for (int i = 0; i < 5; i++) {
            Assertions.assertTrue(process(templateEngine, template, variables, allowedClassOverrides).contains("harmless"));
        }

        // ...and then on a type whose member is forbidden: the same (cached) expression must still reject it
        variables.put("useDanger", Boolean.TRUE);
        for (int i = 0; i < 2; i++) {
            Assertions.assertThrows(Exception.class, () -> process(templateEngine, template, variables, allowedClassOverrides));
        }

        // Going back to the allowed type keeps working
        variables.put("useDanger", Boolean.FALSE);
        Assertions.assertTrue(process(templateEngine, template, variables, allowedClassOverrides).contains("harmless"));

    }


    @Test
    public void testCachedPropertyAccessKeepsMapAccessorPrecedence() {

        final SpringTemplateEngine templateEngine = templateEngine();
        final String template = "<p th:text=\"${(useMap ? map : bean).size}\"></p>";

        final Map<String,Object> map = new HashMap<>();
        map.put("size", "fromMap");

        final Map<String,Object> variables = new HashMap<>();
        variables.put("bean", new Harmless());
        variables.put("map", map);

        variables.put("useMap", Boolean.FALSE);
        for (int i = 0; i < 5; i++) {
            Assertions.assertEquals("<p>42</p>", process(templateEngine, template, variables, null));
        }

        // A map target must still be resolved by the MapAccessor (map.get("size")), not as a bean property (map.size())
        variables.put("useMap", Boolean.TRUE);
        Assertions.assertEquals("<p>fromMap</p>", process(templateEngine, template, variables, null));

        variables.put("useMap", Boolean.FALSE);
        Assertions.assertEquals("<p>42</p>", process(templateEngine, template, variables, null));

    }


    @Test
    public void testCachedPropertyAccessWithDifferentAllowedTypes() {

        final SpringTemplateEngine templateEngine = templateEngine();
        final String template = "<p th:each=\"item : ${items}\" th:text=\"${item.name}\"></p>";

        final Map<String,Object> variables = new HashMap<>();
        variables.put("items", java.util.Arrays.asList(new Harmless(), new OtherHarmless(), new Harmless(), new OtherHarmless()));

        for (int i = 0; i < 3; i++) {
            Assertions.assertEquals("<p>bean</p><p>other</p><p>bean</p><p>other</p>",
                    process(templateEngine, template, variables, null));
        }

    }

}
