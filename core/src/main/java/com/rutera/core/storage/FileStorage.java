package com.rutera.core.storage;

import com.rutera.common.domain.StoredFile;
import com.rutera.common.repository.StoredFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Uploaded files on disk (standalone; S3 comes with the cloud): {@code rutera.storage.dir}/{firma}/{kind}/{yyyy-MM}/
 * {uuid}, described by a {@link StoredFile} row. The bytes follow the transaction: a file written in a transaction
 * that rolls back is removed again, a deleted file disappears from disk only after the commit.
 */
@Service
public class FileStorage {

    private static final Logger log = LoggerFactory.getLogger(FileStorage.class);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

    private final Path root;
    private final StoredFileRepository storedFiles;

    public FileStorage(@Value("${rutera.storage.dir}") String dir, StoredFileRepository storedFiles) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.storedFiles = storedFiles;
    }

    /** Writes the bytes and saves the row; call inside a transaction. */
    public StoredFile store(Long firmaId, String kind, String originalName, String mime, byte[] bytes, String sha256,
                            Long uploadedBy) {
        Instant now = Instant.now();
        String key = firmaId + "/" + kind.toLowerCase() + "/" + MONTH.format(now) + "/" + UUID.randomUUID();
        Path path = resolve(key);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write stored file " + key, e);
        }
        afterRollback(path);

        StoredFile file = new StoredFile();
        file.setFirmaId(firmaId);
        file.setKind(kind);
        file.setStorage(StoredFile.Storage.DISK);
        file.setStorageKey(key);
        file.setOriginalName(originalName);
        file.setMime(mime);
        file.setSizeBytes(bytes.length);
        file.setSha256(sha256);
        file.setUploadedBy(uploadedBy);
        file.setUploadedAt(now);
        return storedFiles.save(file);
    }

    public byte[] read(StoredFile file) {
        try {
            return Files.readAllBytes(resolve(file.getStorageKey()));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read stored file " + file.getStorageKey(), e);
        }
    }

    /** Deletes the row now and the bytes after the commit. */
    public void delete(StoredFile file) {
        Path path = resolve(file.getStorageKey());
        storedFiles.delete(file);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteQuietly(path);
                }
            });
        } else {
            deleteQuietly(path);
        }
    }

    private void afterRollback(Path path) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        deleteQuietly(path);
                    }
                }
            });
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Storage key outside the storage directory");
        }
        return path;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete stored file {}: {}", path, e.toString());
        }
    }
}
