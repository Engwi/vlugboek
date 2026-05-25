alter table app_users alter column role set data type varchar(255);

alter table app_users add column if not exists registered boolean default true;

update app_users
set registered = true
where registered is null;

alter table app_users alter column registered set not null;

update app_users
set role = 'SYSTEM_ADMIN'
where lower(email) = 'admin@vlugboek.local'
  and role = 'ADMIN';
