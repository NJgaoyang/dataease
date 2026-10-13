-- Resource ownership is explicit and independent of upstream BI metadata.
CREATE TABLE IF NOT EXISTS de_ext_resource_registry (
    resource_type VARCHAR(80) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    org_id BIGINT NOT NULL,
    registered_at BIGINT NOT NULL,
    PRIMARY KEY (resource_type, resource_id)
);