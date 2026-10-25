package com.rincoltech.bms.core.documents.internal;

import com.rincoltech.bms.kernel.PersonalDataEraser;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The documents' part of removing a closed business (issue #571, ADR-090): every stored file of the business (member ID images,
 * item photos, logos, report files, feedback screenshots, generated PDFs) is deleted from the object store once the removal
 * commits. The rows stay, as the money records that point at them do; their files are gone. A file that cannot be deleted is
 * logged by its key only and left for the orphan sweep, since the removal itself has committed.
 */
@Component
class DocumentErasure implements PersonalDataEraser {

    private static final Logger log = LoggerFactory.getLogger(DocumentErasure.class);

    private final JdbcClient jdbc;
    private final ObjectStorage storage;

    DocumentErasure(JdbcClient jdbc, ObjectStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    @Override
    public String area() {
        return "documents";
    }

    @Override
    public int erase() {
        List<String> keys = jdbc.sql("SELECT object_key FROM documents ORDER BY created_at, id")
                .query(String.class)
                .list();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (String key : keys) {
                    try {
                        storage.delete(key);
                    } catch (RuntimeException e) {
                        log.warn("could not delete object {} of a removed business", key, e);
                    }
                }
            }
        });
        return keys.size();
    }
}
