package com.bgsoftware.superiorskyblock.config;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code ${NAME}} references to environment variables in config values, so database passwords can stay out of
 * config.yml.
 */
public final class EnvironmentValues {

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private EnvironmentValues() {

    }

    /**
     * Replaces each {@code ${NAME}} in the value with that environment variable.
     *
     * @throws IllegalStateException when a referenced variable is not set
     */
    public static String expand(String value, Map<String, String> environment) {
        if (value == null)
            return null;

        Matcher matcher = REFERENCE.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = environment.get(matcher.group(1));
            if (replacement == null)
                throw new IllegalStateException("Environment variable " + matcher.group(1) + " used in config.yml is not set");
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

}
