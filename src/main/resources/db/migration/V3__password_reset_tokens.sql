alter table app_users add column if not exists password_reset_token_hash varchar(255);
alter table app_users add column if not exists password_reset_expires_at timestamp(6) with time zone;

create index if not exists idx_app_users_password_reset_token_hash on app_users (password_reset_token_hash);
