package com.intland.codebeamer.wiki.plugins.streamreuse.dashboard;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts the existing StreamReuseModels.Report object graph into values that
 * Codebeamer's default Jackson configuration can serialize.
 *
 * Java Time values, including Instant, are converted to ISO-8601 strings.
 */
public final class DashboardReportResponse {

    private static final int MAX_DEPTH = 20;

    private DashboardReportResponse() {
    }

    public static Map<String, Object> from(Object report) {
        Object converted = convert(
                report,
                new IdentityHashMap<>(),
                0);

        if (converted == null) {
            return null;
        }

        if (!(converted instanceof Map)) {
            throw new IllegalStateException(
                    "Expected the dashboard Report to convert to a JSON object.");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result =
                (Map<String, Object>) converted;

        return result;
    }

    private static Object convert(
            Object value,
            IdentityHashMap<Object, Boolean> seen,
            int depth) {

        if (value == null) {
            return null;
        }

        if (depth > MAX_DEPTH) {
            return String.valueOf(value);
        }

        if (value instanceof TemporalAccessor) {
            return value.toString();
        }

        if (value instanceof String ||
                value instanceof Number ||
                value instanceof Boolean ||
                value instanceof Character ||
                value.getClass().isEnum()) {
            return value;
        }

        if (seen.containsKey(value)) {
            return null;
        }

        seen.put(value, Boolean.TRUE);

        try {
            if (value instanceof Map<?, ?>) {
                Map<String, Object> convertedMap =
                        new LinkedHashMap<>();

                for (Map.Entry<?, ?> entry :
                        ((Map<?, ?>) value).entrySet()) {

                    convertedMap.put(
                            String.valueOf(entry.getKey()),
                            convert(
                                    entry.getValue(),
                                    seen,
                                    depth + 1));
                }

                return convertedMap;
            }

            if (value instanceof Collection<?>) {
                List<Object> convertedList =
                        new ArrayList<>();

                for (Object row : (Collection<?>) value) {
                    convertedList.add(
                            convert(
                                    row,
                                    seen,
                                    depth + 1));
                }

                return convertedList;
            }

            if (value.getClass().isArray()) {
                List<Object> convertedArray =
                        new ArrayList<>();

                int length = Array.getLength(value);

                for (int index = 0;
                     index < length;
                     index++) {

                    convertedArray.add(
                            convert(
                                    Array.get(value, index),
                                    seen,
                                    depth + 1));
                }

                return convertedArray;
            }

            Map<String, Object> convertedBean =
                    new LinkedHashMap<>();

            BeanInfo beanInfo =
                    Introspector.getBeanInfo(
                            value.getClass(),
                            Object.class);

            for (PropertyDescriptor property :
                    beanInfo.getPropertyDescriptors()) {

                if (property.getReadMethod() == null) {
                    continue;
                }

                property.getReadMethod().setAccessible(true);

                Object propertyValue =
                        property.getReadMethod()
                                .invoke(value);

                convertedBean.put(
                        property.getName(),
                        convert(
                                propertyValue,
                                seen,
                                depth + 1));
            }

            return convertedBean;

        } catch (IntrospectionException exception) {
            throw new IllegalStateException(
                    "Unable to inspect dashboard response type " +
                            value.getClass().getName() + ".",
                    exception);

        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Unable to read a dashboard response property from " +
                            value.getClass().getName() + ".",
                    exception);

        } finally {
            seen.remove(value);
        }
    }
}
