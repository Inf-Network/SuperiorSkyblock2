package com.bgsoftware.superiorskyblock.config;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EnvironmentValuesTest {

    @Test
    public void replacesEveryReference() {
        Map<String, String> environment = new HashMap<>();
        environment.put("SKYBLOCK_DB_HOST", "127.0.0.1");
        environment.put("SKYBLOCK_SSB2_DB_PASSWORD", "p$a\\ss");

        assertEquals("127.0.0.1", EnvironmentValues.expand("${SKYBLOCK_DB_HOST}", environment));
        assertEquals("p$a\\ss", EnvironmentValues.expand("${SKYBLOCK_SSB2_DB_PASSWORD}", environment));
        assertEquals("jdbc://127.0.0.1/x", EnvironmentValues.expand("jdbc://${SKYBLOCK_DB_HOST}/x", environment));
    }

    @Test
    public void keepsValuesWithoutReferences() {
        assertEquals("root", EnvironmentValues.expand("root", Collections.emptyMap()));
        assertEquals("$HOME", EnvironmentValues.expand("$HOME", Collections.emptyMap()));
        assertNull(EnvironmentValues.expand(null, Collections.emptyMap()));
    }

    @Test
    public void refusesAnUnsetVariable() {
        try {
            EnvironmentValues.expand("${SKYBLOCK_UNSET}", Collections.emptyMap());
            fail("An unset variable must not become an empty password");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("SKYBLOCK_UNSET"));
        }
    }

}
