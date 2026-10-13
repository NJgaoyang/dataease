-- Only addon-owned metadata; V001 columns remain untouched.
ALTER TABLE de_ext_user ADD COLUMN email VARCHAR(255);
ALTER TABLE de_ext_user ADD COLUMN phone_prefix VARCHAR(16);
ALTER TABLE de_ext_user ADD COLUMN phone VARCHAR(64);
ALTER TABLE de_ext_user ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0;