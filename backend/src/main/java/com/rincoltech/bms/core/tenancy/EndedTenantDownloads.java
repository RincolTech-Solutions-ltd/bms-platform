package com.rincoltech.bms.core.tenancy;

import java.util.UUID;

/**
 * Lets the module that makes the business's own data export tell the tenancy module which files a business whose
 * subscription ended may still fetch through {@code POST /documents/{id}/download-url} (ADR-074). Every other document
 * (member ID images, loan papers, report files) stays refused with 423 {@code subscription_ended}, as before the export.
 * Called inside a request whose tenant is resolved; the implementation reads under that tenant's row level security.
 */
public interface EndedTenantDownloads {

    /** True when the document is the file of one of this business's data export runs. */
    boolean isDataExportFile(UUID documentId);
}
