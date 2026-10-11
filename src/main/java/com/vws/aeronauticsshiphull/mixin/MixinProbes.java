package com.vws.aeronauticsshiphull.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Names of mixins whose {@code postApply} ran. A missing target never initializes this set for
 * that mixin, so startup can refuse to park instead of crashing.
 */
public final class MixinProbes {
    private static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();

    private MixinProbes() {}

    public static void applied(String mixinClassName) {
        if (mixinClassName == null || mixinClassName.isEmpty()) return;
        int dot = mixinClassName.lastIndexOf('.');
        APPLIED.add(dot >= 0 ? mixinClassName.substring(dot + 1) : mixinClassName);
    }

    public static List<String> missing(String... names) {
        List<String> out = new ArrayList<>();
        for (String name : names) {
            if (!APPLIED.contains(name)) out.add(name);
        }
        return out;
    }
}
