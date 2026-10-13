package io.dataease.enhance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/enhance/permissions")
@ConditionalOnProperty(prefix="dataease.enhance",name="enabled",havingValue="true")
public class EnhanceDatasetRowController {
    private final EnhanceDatasetRowPolicy policy;
    public EnhanceDatasetRowController(EnhanceDatasetRowPolicy policy) {
        this.policy=policy;
    }

    @PutMapping("/roles/{roleId}/datasets/{datasetId}/rows")
    public void replace(@PathVariable long roleId,@PathVariable long datasetId,
                        @RequestBody List<EnhanceDatasetRowPolicy.Rule> rules) {
        policy.replace(roleId,datasetId,rules);
    }
}