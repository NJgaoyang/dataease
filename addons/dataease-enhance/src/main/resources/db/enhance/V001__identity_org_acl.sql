-- Owned by dataease-enhance. Never modify DataEase upstream tables.
CREATE TABLE IF NOT EXISTS de_ext_user (
  id BIGINT NOT NULL PRIMARY KEY,
  account VARCHAR(128) NOT NULL UNIQUE,
  display_name VARCHAR(255) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  enabled INTEGER NOT NULL,
  failed_attempts INTEGER NOT NULL,
  locked_until BIGINT NOT NULL,
  token_version INTEGER NOT NULL,
  created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS de_ext_org (
  id BIGINT NOT NULL PRIMARY KEY,
  parent_id BIGINT,
  name VARCHAR(255) NOT NULL,
  enabled INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS de_ext_membership (
  user_id BIGINT NOT NULL,
  org_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, org_id)
);
CREATE TABLE IF NOT EXISTS de_ext_role (
  id BIGINT NOT NULL PRIMARY KEY,
  org_id BIGINT NOT NULL,
  code VARCHAR(128) NOT NULL,
  name VARCHAR(255) NOT NULL,
  system_role INTEGER NOT NULL,
  UNIQUE (org_id, code)
);
CREATE TABLE IF NOT EXISTS de_ext_user_role (
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, role_id)
);
CREATE TABLE IF NOT EXISTS de_ext_menu_acl (
  role_id BIGINT NOT NULL,
  menu_code VARCHAR(128) NOT NULL,
  PRIMARY KEY (role_id, menu_code)
);
CREATE TABLE IF NOT EXISTS de_ext_resource_acl (
  role_id BIGINT NOT NULL,
  resource_type VARCHAR(80) NOT NULL,
  resource_id VARCHAR(128) NOT NULL,
  action VARCHAR(32) NOT NULL,
  PRIMARY KEY (role_id, resource_type, resource_id, action)
);
CREATE TABLE IF NOT EXISTS de_ext_audit (
  id BIGINT NOT NULL PRIMARY KEY,
  actor_id BIGINT NOT NULL,
  action VARCHAR(120) NOT NULL,
  subject VARCHAR(255) NOT NULL,
  created_at BIGINT NOT NULL
);