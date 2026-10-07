CREATE TABLE IF NOT EXISTS documents (
    id           uuid PRIMARY KEY,
    name         varchar(255),
    data         bytea,
    content_type varchar(255),
    uploaded_at  timestamp(6)
);
