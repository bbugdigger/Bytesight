package com.bugdigger.sample.services;

import com.bugdigger.sample.models.Status;
import com.bugdigger.sample.models.User;
import com.bugdigger.sample.util.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * User service demonstrating various service patterns and lambda usage.
 */
public class UserService {

    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Logger logger;

    public UserService(Logger logger) {
        this.logger = logger;
    }

    public User createUser(String username, String email) {
        String id = UUID.randomUUID().toString();
        User user = new User(id, username, email);
        users.put(id, user);
        logger.info("Created user: " + username);
        return user;
    }

    public User findUserById(String id) {
        User user = users.get(id);
        if (user == null) {
            throw new NoSuchElementException("User not found: " + id);
        }
        return user;
    }

    public Optional<User> findUserByUsername(String username) {
        return users.values().stream()
            .filter(u -> u.getUsername().equals(username))
            .findFirst();
    }

    public List<User> findUsersByStatus(Status status) {
        return users.values().stream()
            .filter(u -> u.getStatus() == status)
            .collect(Collectors.toList());
    }

    public List<User> findUsers(Predicate<User> predicate) {
        return users.values().stream()
            .filter(predicate)
            .collect(Collectors.toList());
    }

    public void updateUserStatus(String userId, Status newStatus) {
        User user = findUserById(userId);
        Status oldStatus = user.getStatus();
        user.setStatus(newStatus);
        logger.info(String.format("User %s status changed: %s -> %s",
            user.getUsername(), oldStatus, newStatus));
    }

    public void updateUserEmail(String userId, String newEmail) {
        User user = findUserById(userId);
        user.setEmail(newEmail);
        logger.debug("Updated email for user: " + user.getUsername());
    }

    public boolean deleteUser(String userId) {
        User removed = users.remove(userId);
        if (removed != null) {
            logger.info("Deleted user: " + removed.getUsername());
            return true;
        }
        return false;
    }

    public int countActiveUsers() {
        return (int) users.values().stream()
            .filter(User::isActive)
            .count();
    }

    public List<String> getActiveUsernames() {
        return users.values().stream()
            .filter(User::isActive)
            .map(User::getUsername)
            .sorted()
            .collect(Collectors.toList());
    }

    public Map<Status, Long> getUserCountByStatus() {
        return users.values().stream()
            .collect(Collectors.groupingBy(User::getStatus, Collectors.counting()));
    }

    /**
     * Inner class demonstrating nested class patterns.
     */
    public static class UserStats {
        private final int totalUsers;
        private final int activeUsers;
        private final int pendingUsers;

        public UserStats(int totalUsers, int activeUsers, int pendingUsers) {
            this.totalUsers = totalUsers;
            this.activeUsers = activeUsers;
            this.pendingUsers = pendingUsers;
        }

        public int getTotalUsers() {
            return totalUsers;
        }

        public int getActiveUsers() {
            return activeUsers;
        }

        public int getPendingUsers() {
            return pendingUsers;
        }

        public double getActivePercentage() {
            return totalUsers > 0 ? (double) activeUsers / totalUsers * 100 : 0;
        }
    }

    public UserStats getStats() {
        int total = users.size();
        int active = countActiveUsers();
        int pending = (int) users.values().stream()
            .filter(u -> u.getStatus() == Status.PENDING)
            .count();
        return new UserStats(total, active, pending);
    }
}
