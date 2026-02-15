package com.bugdigger.sample.models;

/**
 * Category enum for products.
 */
public enum Category {
    ELECTRONICS("Electronics", 0.08),
    CLOTHING("Clothing", 0.05),
    FOOD("Food", 0.0),
    BOOKS("Books", 0.0),
    HOME("Home & Garden", 0.06),
    OTHER("Other", 0.07);

    private final String displayName;
    private final double taxRate;

    Category(String displayName, double taxRate) {
        this.displayName = displayName;
        this.taxRate = taxRate;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double getTaxRate() {
        return taxRate;
    }
}
