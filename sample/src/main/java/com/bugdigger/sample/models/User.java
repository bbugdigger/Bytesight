package com.bugdigger.sample.models;

import java.time.Instant;
import java.util.Objects;

/**
 * User model class demonstrating standard POJO patterns.
 */
public class User {

    private final String id;
    private String username;
    private String email;
    private Status status;
    private final Instant createdAt;
    private Instant updatedAt;

    public User(String id, String username, String email) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.username = Objects.requireNonNull(username, "username cannot be null");
        this.email = email;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = Objects.requireNonNull(username);
        this.updatedAt = Instant.now();
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
        this.updatedAt = Instant.now();
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = Objects.requireNonNull(status);
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return Objects.equals(id, user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "User{" +
            "id='" + id + '\'' +
            ", username='" + username + '\'' +
            ", email='" + email + '\'' +
            ", status=" + status +
            '}';
    }
}
