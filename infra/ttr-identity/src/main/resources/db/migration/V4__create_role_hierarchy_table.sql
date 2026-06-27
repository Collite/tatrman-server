CREATE TABLE role_hierarchy (
    parent_role VARCHAR(255) NOT NULL,
    child_role VARCHAR(255) NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    PRIMARY KEY (parent_role, child_role, source_type)
);

CREATE INDEX idx_role_hierarchy_source ON role_hierarchy(source_type);
