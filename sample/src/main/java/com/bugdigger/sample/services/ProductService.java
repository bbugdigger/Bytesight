package com.bugdigger.sample.services;

import com.bugdigger.sample.models.Category;
import com.bugdigger.sample.models.Product;
import com.bugdigger.sample.util.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Product service with various business logic methods.
 */
public class ProductService {

    private final Map<String, Product> products = new ConcurrentHashMap<>();
    private final Logger logger;

    public ProductService(Logger logger) {
        this.logger = logger;
    }

    public Product createProduct(String name, double price, Category category) {
        String id = UUID.randomUUID().toString();
        Product product = new Product(id, name, price, category);
        products.put(id, product);
        logger.info("Created product: " + name + " ($" + String.format("%.2f", price) + ")");
        return product;
    }

    public Product findProductById(String id) {
        Product product = products.get(id);
        if (product == null) {
            throw new NoSuchElementException("Product not found: " + id);
        }
        return product;
    }

    public List<Product> findProductsByCategory(Category category) {
        return products.values().stream()
            .filter(p -> p.getCategory() == category)
            .collect(Collectors.toList());
    }

    public List<Product> findProductsInPriceRange(double minPrice, double maxPrice) {
        return products.values().stream()
            .filter(p -> p.getPrice() >= minPrice && p.getPrice() <= maxPrice)
            .sorted(Comparator.comparingDouble(Product::getPrice))
            .collect(Collectors.toList());
    }

    public List<Product> findInStockProducts() {
        return products.values().stream()
            .filter(Product::isInStock)
            .collect(Collectors.toList());
    }

    public void updateStock(String productId, int quantity) {
        Product product = findProductById(productId);
        int oldQuantity = product.getStockQuantity();
        product.setStockQuantity(quantity);
        logger.debug(String.format("Stock updated for %s: %d -> %d",
            product.getName(), oldQuantity, quantity));
    }

    public void updatePrice(String productId, double newPrice) {
        Product product = findProductById(productId);
        double oldPrice = product.getPrice();
        product.setPrice(newPrice);
        logger.info(String.format("Price updated for %s: $%.2f -> $%.2f",
            product.getName(), oldPrice, newPrice));
    }

    public double calculateDiscountedPrice(String productId, double discountRate) {
        Product product = findProductById(productId);
        double discountedPrice = product.getPrice() * (1 - discountRate);
        logger.debug(String.format("Calculated discount for %s: %.0f%% off = $%.2f",
            product.getName(), discountRate * 100, discountedPrice));
        return discountedPrice;
    }

    public double calculateTotalInventoryValue() {
        return products.values().stream()
            .mapToDouble(p -> p.getPrice() * p.getStockQuantity())
            .sum();
    }

    public Map<Category, Double> getAveragePriceByCategory() {
        return products.values().stream()
            .collect(Collectors.groupingBy(
                Product::getCategory,
                Collectors.averagingDouble(Product::getPrice)
            ));
    }

    public Optional<Product> findCheapestProduct() {
        return products.values().stream()
            .min(Comparator.comparingDouble(Product::getPrice));
    }

    public Optional<Product> findMostExpensiveProduct() {
        return products.values().stream()
            .max(Comparator.comparingDouble(Product::getPrice));
    }

    public boolean deleteProduct(String productId) {
        Product removed = products.remove(productId);
        if (removed != null) {
            logger.info("Deleted product: " + removed.getName());
            return true;
        }
        return false;
    }

    public int getTotalProductCount() {
        return products.size();
    }

    public int getTotalStockCount() {
        return products.values().stream()
            .mapToInt(Product::getStockQuantity)
            .sum();
    }
}
