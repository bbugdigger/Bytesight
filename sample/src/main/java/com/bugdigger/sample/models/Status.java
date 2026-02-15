package com.bugdigger.sample.models;

/**
 * Status enum demonstrating enum patterns.
 */
public enum Status {
    PENDING("Pending", false),
    ACTIVE("Active", true),
    SUSPENDED("Suspended", false),
    DELETED("Deleted", false);

    private final String displayName;
    private final boolean canPerformActions;

    Status(String displayName, boolean canPerformActions) {
        this.displayName = displayName;
        this.canPerformActions = canPerformActions;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean canPerformActions() {
        return canPerformActions;
    }

    public static Status fromString(String value) {
        for (Status status : values()) {
            if (status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown status: " + value);
    }
}
