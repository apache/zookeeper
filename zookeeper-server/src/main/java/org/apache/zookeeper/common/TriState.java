package org.apache.zookeeper.common;

/**
 * Represent True / False / System (unset/default) values.
 * 
 */
public enum TriState {
    True,
    False,
    System;

	/**
	 * @param value the string representation
	 * @return TriState.true if value equals "true" ignoring case, TriState.System
	 *         if value equals "system", Tristate.False otherwise
	 */
    public static TriState parse(String value) {
        if (value == null) {
            return TriState.False;
        } else if (value.equalsIgnoreCase("true")) {
            return TriState.True;
        } else if (value.equalsIgnoreCase("system")) {
            return TriState.System;
        } else {
            return TriState.False;
        }
    }

    public boolean isTrue() {
        return this == TriState.True;
    }

    public boolean isFalse() {
        return this == TriState.False;
    }

    public boolean isSystem() {
        return this == TriState.System;
    }
}
