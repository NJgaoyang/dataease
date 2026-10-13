package io.dataease.enhance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/enhance/permissions")
@ConditionalOnProperty(prefix="dataease.enhance",name="enabled",havingValue="true")
public class EnhanceDatasetColumnController {
    private final EnhanceDatasetColumnPolicy policy;
    public EnhanceDatasetColumnController(EnhanceDatasetColumnPolicy policy) {
        this.policy=policy;
    }

    @PutMapping("/roles/{roleId}/datasets/{datasetId}/columns")
    public void replace(@PathVariable long roleId,@PathVariable long datasetId,
                        @RequestBody List<Long> fieldIds) {
        policy.replaceColumns(roleId,datasetId,fieldIds);
    }
}