package com.rutera.core.tacho;

import com.rutera.common.context.RequestContext;
import com.rutera.common.domain.StoredFile;
import com.rutera.common.domain.TachoFileRecord;
import com.rutera.common.repository.StoredFileRepository;
import com.rutera.common.repository.TachoFileRepository;
import com.rutera.core.storage.FileStorage;
import com.rutera.tacho.TachoParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * At start, extracts the facts of tachograph files stored before the current {@link TachoFacts#VERSION} (files
 * uploaded before step B, or after a change of the extraction) and recomputes the violations of their drivers.
 * One transaction per file; a file that cannot be read is logged (without personal data) and left as it is.
 */
@Component
public class TachoFactsBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TachoFactsBackfill.class);

    private final TachoFileRepository files;
    private final StoredFileRepository storedFiles;
    private final FileStorage storage;
    private final TachoImportService importService;
    private final TransactionTemplate tx;

    public TachoFactsBackfill(TachoFileRepository files, StoredFileRepository storedFiles, FileStorage storage,
                              TachoImportService importService, PlatformTransactionManager transactionManager) {
        this.files = files;
        this.storedFiles = storedFiles;
        this.storage = storage;
        this.importService = importService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<Object[]> pending = RequestContext.runAsRoot(null, () -> tx.execute(status ->
                files.findPendingFacts(TachoFacts.VERSION)));
        if (pending == null || pending.isEmpty()) {
            return;
        }
        log.info("Extracting tachograph facts of {} stored file(s)", pending.size());
        for (Object[] p : pending) {
            Long id = (Long) p[0];
            Long firmaId = (Long) p[1];
            try {
                RequestContext.runAsFirma(firmaId, null, () -> tx.execute(status -> {
                    TachoFileRecord f = files.findById(id).orElseThrow();
                    StoredFile stored = storedFiles.findById(f.getFileId()).orElseThrow();
                    importService.extractFacts(f, TachoParser.parse(storage.read(stored)));
                    return null;
                }));
            } catch (Exception e) {
                log.warn("Tachograph file {} could not be processed: {}", id, e.toString());
            }
        }
    }
}
