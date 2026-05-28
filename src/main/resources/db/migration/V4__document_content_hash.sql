alter table documents add column if not exists content_sha256 varchar(64);

create unique index if not exists uk_documents_content_sha256 on documents (content_sha256);
