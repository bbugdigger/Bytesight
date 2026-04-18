package com.bugdigger.sample;

import com.bugdigger.sample.models.*;
import com.bugdigger.sample.services.DataProcessor;
import com.bugdigger.sample.services.HeapDemoData;
import com.bugdigger.sample.services.UserService;
import com.bugdigger.sample.services.ProductService;
import com.bugdigger.sample.util.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sample application demonstrating various Java features for testing
 * decompilation and method tracing with Bytesight.
 */
public class SampleApplication {

    private final UserService userService;
    private final ProductService productService;
    private final DataProcessor dataProcessor;
    private final HeapDemoData heapDemoData;
    private final Logger logger;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running;

    public SampleApplication() {
        this.logger = new Logger("SampleApp");
        this.userService = new UserService(logger);
        this.productService = new ProductService(logger);
        this.dataProcessor = new DataProcessor(logger);
        this.heapDemoData = new HeapDemoData(logger);
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.running = false;
    }

    public void start() {
        logger.info("Starting Sample Application...");
        running = true;

        // Populate heap with demo objects for heap inspection testing
        heapDemoData.populate();

        // Schedule periodic tasks
        scheduler.scheduleAtFixedRate(this::simulateUserActivity, 0, 2, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::simulateProductActivity, 1, 3, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::simulateDataProcessing, 2, 4, TimeUnit.SECONDS);

        logger.info("Sample Application started. Press Ctrl+C to stop.");

        // Keep the application running
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

        try {
            while (running) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        logger.info("Stopping Sample Application...");
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("Sample Application stopped.");
    }

    private void simulateUserActivity() {
        try {
            // Create a new user
            User user = userService.createUser("user_" + System.currentTimeMillis(), "test@example.com");
            logger.debug("Created user: " + user.getUsername());

            // Perform some operations
            userService.updateUserStatus(user.getId(), Status.ACTIVE);

            // Sometimes trigger an exception
            if (Math.random() < 0.1) {
                userService.findUserById("non-existent-id");
            }
        } catch (Exception e) {
            logger.warn("User activity simulation error: " + e.getMessage());
        }
    }

    private void simulateProductActivity() {
        try {
            // Create a product
            Product product = productService.createProduct(
                "Product " + System.currentTimeMillis(),
                Math.random() * 100,
                Category.ELECTRONICS
            );
            logger.debug("Created product: " + product.getName());

            // Update stock
            productService.updateStock(product.getId(), (int) (Math.random() * 50));

            // Calculate some prices
            double discountedPrice = productService.calculateDiscountedPrice(product.getId(), 0.15);
            logger.debug("Discounted price: " + discountedPrice);
        } catch (Exception e) {
            logger.warn("Product activity simulation error: " + e.getMessage());
        }
    }

    private static final String[] SAMPLE_INPUTS = {
        "HelloWorld", "Bytesight42", "CFG", "abc123XYZ", "ALLCAPS",
        "", "lowercaseonly", "12345", "MiXeD!CaSe", null,
    };

    private void simulateDataProcessing() {
        try {
            String input = SAMPLE_INPUTS[(int) (Math.random() * SAMPLE_INPUTS.length)];
            int iterations = 3 + (int) (Math.random() * 12);
            String result = dataProcessor.processData(input, iterations);
            logger.debug("Data processing result: " + result);
        } catch (Exception e) {
            logger.warn("Data processing error: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        SampleApplication app = new SampleApplication();
        app.start();
    }
}
