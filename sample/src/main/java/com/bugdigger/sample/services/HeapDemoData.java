package com.bugdigger.sample.services;

import com.bugdigger.sample.models.*;
import com.bugdigger.sample.util.Logger;

import java.util.*;

/**
 * Creates objects specifically designed to exercise Bytesight's heap inspection features:
 * duplicate strings, deep object graphs, varied class instances, and searchable values.
 */
public class HeapDemoData {

    private final Logger logger;

    // ========== Retained references (prevent GC) ==========
    private final List<String> duplicateStrings = new ArrayList<>();
    private final List<TreeNode> treeNodes = new ArrayList<>();
    private final List<LinkedNode> linkedNodes = new ArrayList<>();
    private final List<User> demoUsers = new ArrayList<>();
    private final List<Product> demoProducts = new ArrayList<>();
    private final List<Container> containers = new ArrayList<>();

    public HeapDemoData(Logger logger) {
        this.logger = logger;
    }

    /**
     * Populates heap with interesting objects for demo/testing.
     */
    public void populate() {
        createDuplicateStrings();
        createDeepLinkedList();
        createBinaryTree();
        createVariedInstances();
        createContainerGraph();
        logger.info("HeapDemoData populated: " +
            duplicateStrings.size() + " dup strings, " +
            linkedNodes.size() + " linked nodes, " +
            treeNodes.size() + " tree nodes, " +
            demoUsers.size() + " users, " +
            demoProducts.size() + " products, " +
            containers.size() + " containers");
    }

    // ========== Duplicate Strings ==========

    private void createDuplicateStrings() {
        // Deliberately create duplicate String objects with new String() to bypass interning
        String[] phrases = {
            "hello world", "error: connection refused", "null pointer exception",
            "bytesight", "sample application", "duplicate value"
        };
        for (String phrase : phrases) {
            for (int i = 0; i < 5; i++) {
                duplicateStrings.add(new String(phrase));  // forces distinct heap objects
            }
        }
        // A few with higher duplication counts
        for (int i = 0; i < 20; i++) {
            duplicateStrings.add(new String("HIGHLY_DUPLICATED_CONFIG_KEY"));
        }
        for (int i = 0; i < 12; i++) {
            duplicateStrings.add(new String("/api/v1/users"));
        }
    }

    // ========== Deep Linked List ==========

    /**
     * Simple singly-linked list node — creates a long reference chain for object graph testing.
     */
    public static class LinkedNode {
        private final int index;
        private final String label;
        private LinkedNode next;

        public LinkedNode(int index, String label) {
            this.index = index;
            this.label = label;
        }

        public int getIndex() { return index; }
        public String getLabel() { return label; }
        public LinkedNode getNext() { return next; }
        public void setNext(LinkedNode next) { this.next = next; }
    }

    private void createDeepLinkedList() {
        LinkedNode prev = null;
        for (int i = 0; i < 50; i++) {
            LinkedNode node = new LinkedNode(i, "node-" + i);
            if (prev != null) {
                prev.setNext(node);
            }
            linkedNodes.add(node);
            prev = node;
        }
    }

    // ========== Binary Tree ==========

    /**
     * Binary tree node — produces a branching object graph.
     */
    public static class TreeNode {
        private final String name;
        private final int value;
        private TreeNode left;
        private TreeNode right;

        public TreeNode(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }
        public int getValue() { return value; }
        public TreeNode getLeft() { return left; }
        public TreeNode getRight() { return right; }
    }

    private void createBinaryTree() {
        TreeNode root = buildTree("root", 0, 4);
        treeNodes.add(root);
    }

    private TreeNode buildTree(String prefix, int depth, int maxDepth) {
        TreeNode node = new TreeNode(prefix + "-d" + depth, depth * 10);
        treeNodes.add(node);
        if (depth < maxDepth) {
            node.left = buildTree(prefix + "L", depth + 1, maxDepth);
            node.right = buildTree(prefix + "R", depth + 1, maxDepth);
        }
        return node;
    }

    // ========== Varied Model Instances ==========

    private void createVariedInstances() {
        // Users with different statuses
        String[] names = {"alice", "bob", "charlie", "diana", "eve", "frank", "grace", "heidi"};
        Status[] statuses = {Status.ACTIVE, Status.PENDING, Status.SUSPENDED, Status.ACTIVE,
                             Status.ACTIVE, Status.DELETED, Status.PENDING, Status.ACTIVE};
        for (int i = 0; i < names.length; i++) {
            User u = new User(UUID.randomUUID().toString(), names[i], names[i] + "@example.com");
            u.setStatus(statuses[i]);
            demoUsers.add(u);
        }

        // Products across all categories with searchable names
        Category[] cats = Category.values();
        String[] productNames = {
            "Wireless Mouse", "Cotton T-Shirt", "Organic Coffee", "Design Patterns Book",
            "Garden Hose", "USB-C Cable", "Running Shoes", "Green Tea", "Clean Code Book",
            "LED Lamp", "Bluetooth Speaker", "Wool Sweater"
        };
        for (int i = 0; i < productNames.length; i++) {
            Product p = new Product(
                UUID.randomUUID().toString(),
                productNames[i],
                5.0 + (i * 7.5),
                cats[i % cats.length]
            );
            p.setStockQuantity(i * 3);
            demoProducts.add(p);
        }
    }

    // ========== Container Graph (multi-reference) ==========

    /**
     * A container that references other containers — tests object graphs with shared references.
     */
    public static class Container {
        private final String id;
        private final List<Container> children = new ArrayList<>();
        private final Map<String, Object> metadata = new HashMap<>();

        public Container(String id) {
            this.id = id;
        }

        public String getId() { return id; }
        public List<Container> getChildren() { return children; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void addChild(Container child) { children.add(child); }
    }

    private void createContainerGraph() {
        // Create a DAG with shared children
        Container root = new Container("root");
        Container a = new Container("child-A");
        Container b = new Container("child-B");
        Container shared = new Container("shared-node");

        root.addChild(a);
        root.addChild(b);
        a.addChild(shared);     // shared is referenced by both a and b
        b.addChild(shared);

        root.getMetadata().put("type", "root");
        root.getMetadata().put("count", 42);
        a.getMetadata().put("type", "branch");
        shared.getMetadata().put("type", new String("shared-leaf"));  // searchable string in map

        containers.add(root);
        containers.add(a);
        containers.add(b);
        containers.add(shared);
    }
}
