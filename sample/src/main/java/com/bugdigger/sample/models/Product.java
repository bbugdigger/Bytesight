package com.bugdigger.sample.models;

import java.time.Instant;
import java.util.Objects;

/**
 * Product model class with various data types.
 */
public class Product {

    private final String id;
    private String name;
    private double price;
    private int stockQuantity;
    private Category category;
    private final Instant createdAt;

    public Product(String id, String name, double price, Category category) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.price = price;
        this.category = Objects.requireNonNull(category);
        this.stockQuantity = 0;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = Objects.requireNonNull(name);
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        if (price < 0) {
            throw new IllegalArgumentException("Price cannot be negative");
        }
        this.price = price;
    }

    public int getStockQuantity() {
        return stockQuantity;
    }

    public void setStockQuantity(int stockQuantity) {
        if (stockQuantity < 0) {
            throw new IllegalArgumentException("Stock quantity cannot be negative");
        }
        this.stockQuantity = stockQuantity;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = Objects.requireNonNull(category);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isInStock() {
        return stockQuantity > 0;
    }

    public double calculateTax(double taxRate) {
        return price * taxRate;
    }

    public double getPriceWithTax(double taxRate) {
        return price + calculateTax(taxRate);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Product product = (Product) o;
        return Objects.equals(id, product.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Product{" +
            "id='" + id + '\'' +
            ", name='" + name + '\'' +
            ", price=" + price +
            ", category=" + category +
            ", inStock=" + isInStock() +
            '}';
    }
}
