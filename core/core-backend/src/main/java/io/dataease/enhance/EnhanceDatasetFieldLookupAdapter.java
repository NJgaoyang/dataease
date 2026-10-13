package io.dataease.enhance;

import io.dataease.dataset.manage.DatasetTableFieldManage;
import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

/** Resolve row predicate fields from trusted persisted DataEase metadata. */
@Component
@ConditionalOnProperty(prefix="dataease.enhance",name="enabled",havingValue="true")
public class EnhanceDatasetFieldLookupAdapter implements EnhanceDatasetFieldLookup {
    private final DatasetTableFieldManage fields;
    public EnhanceDatasetFieldLookupAdapter(DatasetTableFieldManage fields) {
        this.fields=fields;
    }

    @Override
    public DatasetTableFieldDTO requireField(long datasetId,long fieldId) {
        if(datasetId<=0 || fieldId<=0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid dataset/field");
        DatasetTableFieldDTO field=fields.selectById(fieldId);
        if(field==null || !Objects.equals(field.getDatasetGroupId(),datasetId) ||
                field.getDataeaseName()==null || field.getDataeaseName().isBlank() ||
                field.getDeType()==null || field.getDeExtractType()==null ||
                !java.util.Set.of(0,2,3,4).contains(field.getDeType()) ||
                !java.util.Set.of(0,1,2,3,4,5).contains(field.getDeExtractType()) ||
                (field.getExtField()!=null && field.getExtField()!=0)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Row predicate field must be an existing ordinary text/numeric dataset field");
        }
        return field;
    }
}