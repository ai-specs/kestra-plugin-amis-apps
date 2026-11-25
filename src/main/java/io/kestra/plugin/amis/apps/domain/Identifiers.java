package io.kestra.plugin.amis.apps.domain;

import java.util.regex.Pattern;

public final class Identifiers {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_-]{0,99}");
    private static final Pattern FLOW = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]*");
    private static final Pattern NAMESPACE = Pattern.compile("^[a-z0-9][a-z0-9_-]*(\\.[a-z0-9_-]+)*");

    private Identifiers() { }

    public static String id(String value) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid page/api identifier");
        }
        return value;
    }

    public static String flowId(String value) {
        if (value == null || value.length() > 100 || !FLOW.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Flow identifier");
        }
        return value;
    }

    public static String namespace(String value) {
        if (value == null || value.length() > 150 || !NAMESPACE.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid namespace");
        }
        return value;
    }
}
