package com.rutera.common.repository;

import com.rutera.common.domain.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Tenant-filtered: results always belong to the firma of the current request context. Top level (not nested in
 * {@link SystemRepositories}): Spring Data does not register nested repository interfaces by default.
 */
public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {

    Optional<StoredFile> findByStorageKey(String storageKey);
}
