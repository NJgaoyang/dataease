package io.dataease.enhance;

import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;

/** Implemented in core-backend to avoid an addon-to-core dependency cycle. */
public interface EnhanceDatasetFieldLookup {
    /** Fail closed if field is absent or not part of the supplied dataset. */
    DatasetTableFieldDTO requireField(long datasetId, long fieldId);
}