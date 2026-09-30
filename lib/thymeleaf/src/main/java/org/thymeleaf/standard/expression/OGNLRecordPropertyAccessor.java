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
package org.thymeleaf.standard.expression;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ognl.ObjectPropertyAccessor;
import ognl.OgnlContext;
import ognl.OgnlException;
import ognl.OgnlRuntime;

/**
 * <p>
 *   Property accessor used by OGNL for Java records.
 * </p>
 * <p>
 *   Record component accessors ({@code name()}) are not JavaBeans getters, so
 *   {@link OgnlRuntime#getGetMethod(OgnlContext, Class, String)} does not find them and OGNL falls back to
 *   {@link OgnlRuntime#getReadMethod(Class, String, Class[])}, which scans (and lower-cases the names of) every
 *   public method of the class on each property access, without caching the result.
 * </p>
 * <p>
 *   This accessor behaves exactly like {@link ObjectPropertyAccessor} but memoizes the result of
 *   {@code getReadMethod} per class and property name. Member access checks are still applied on every access.
 * </p>
 * <p>
 *   Only registered when running on a JVM that supports records (Java 16+).
 * </p>
 *
 * @since 3.1.6
 */
final class OGNLRecordPropertyAccessor extends ObjectPropertyAccessor {

    static final Class<?> RECORD_CLASS = findRecordClass();

    // Marks a cached "no read method" result (ConcurrentHashMap does not allow null values)
    private static final Object NO_READ_METHOD = new Object();

    // ClassValue so that cached entries do not prevent class (and class loader) unloading
    private final ClassValue<Map<String,Object>> readMethods = new ClassValue<Map<String,Object>>() {
        @Override
        protected Map<String,Object> computeValue(final Class<?> type) {
            return new ConcurrentHashMap<String,Object>(8);
        }
    };


    OGNLRecordPropertyAccessor() {
        super();
    }


    private static Class<?> findRecordClass() {
        try {
            return Class.forName("java.lang.Record");
        } catch (final ClassNotFoundException e) {
            return null;
        }
    }


    @Override
    public Object getPossibleProperty(final Map context, final Object target, final String name) throws OgnlException {

        // Mirrors ObjectPropertyAccessor#getPossibleProperty + OgnlRuntime#getMethodValue, with the only
        // difference of caching the result of OgnlRuntime#getReadMethod

        final OgnlContext ognlContext = (OgnlContext) context;

        try {

            final Class<?> targetClass = target.getClass();
            Method method = OgnlRuntime.getGetMethod(ognlContext, targetClass, name);
            if (method == null) {
                method = getReadMethod(targetClass, name);
            }

            if (method != null && ognlContext.getMemberAccess().isAccessible(ognlContext, target, method, name)) {
                try {
                    return OgnlRuntime.invokeMethod(target, method, OgnlRuntime.NoArguments);
                } catch (final InvocationTargetException e) {
                    throw new OgnlException(name, e.getTargetException());
                }
            }

            return OgnlRuntime.getFieldValue(ognlContext, target, name, true);

        } catch (final OgnlException e) {
            throw e;
        } catch (final Exception e) {
            throw new OgnlException(name, e);
        }

    }


    private Method getReadMethod(final Class<?> targetClass, final String name) {
        final Map<String,Object> classReadMethods = this.readMethods.get(targetClass);
        Object method = classReadMethods.get(name);
        if (method == null) {
            method = OgnlRuntime.getReadMethod(targetClass, name, null);
            if (method == null) {
                method = NO_READ_METHOD;
            }
            classReadMethods.put(name, method);
        }
        return (method == NO_READ_METHOD ? null : (Method) method);
    }

}
