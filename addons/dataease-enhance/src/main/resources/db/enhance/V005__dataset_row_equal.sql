CREATE TABLE IF NOT EXISTS de_ext_dataset_row_eq (
 id BIGINT NOT NULL PRIMARY KEY,
 role_id BIGINT NOT NULL,
 dataset_id VARCHAR(128) NOT NULL,
 field_id BIGINT NOT NULL,
 field_value VARCHAR(255) NOT NULL,
 UNIQUE (role_id, dataset_id, field_id, field_value)
);