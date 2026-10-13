-- Explicit least-privilege dataset field allowlist, independent of upstream BI metadata.
CREATE TABLE IF NOT EXISTS de_ext_dataset_column_allow (
    role_id BIGINT NOT NULL,
    dataset_id VARCHAR(128) NOT NULL,
    field_id BIGINT NOT NULL,
    PRIMARY KEY (role_id,dataset_id,field_id)
);