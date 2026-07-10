CREATE TABLE roles (
    id VARCHAR(255) NOT NULL,
    code VARCHAR(255) NOT NULL,
    description VARCHAR(500),
    source VARCHAR(50) NOT NULL,
    PRIMARY KEY (id, source)
);

CREATE INDEX idx_roles_source ON roles(source);
