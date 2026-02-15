package com.bugdigger.sample.services;

import java.util.List;
import java.util.Optional;

/**
 * Generic repository interface demonstrating generics and default methods.
 * 
 * @param <T> The entity type
 * @param <ID> The ID type
 */
public interface Repository<T, ID> {

    /**
     * Saves an entity.
     */
    T save(T entity);

    /**
     * Finds an entity by its ID.
     */
    Optional<T> findById(ID id);

    /**
     * Returns all entities.
     */
    List<T> findAll();

    /**
     * Deletes an entity by its ID.
     */
    boolean deleteById(ID id);

    /**
     * Counts all entities.
     */
    long count();

    /**
     * Checks if an entity exists by its ID.
     */
    default boolean existsById(ID id) {
        return findById(id).isPresent();
    }

    /**
     * Deletes all entities.
     */
    default void deleteAll() {
        findAll().forEach(entity -> {
            // This is a simplified implementation
            // In a real scenario, we'd need the ID extraction logic
        });
    }

    /**
     * Checks if the repository is empty.
     */
    default boolean isEmpty() {
        return count() == 0;
    }
}
