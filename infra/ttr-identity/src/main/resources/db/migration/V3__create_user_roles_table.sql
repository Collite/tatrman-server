CREATE TABLE user_roles (
    internal_user_id BIGINT NOT NULL REFERENCES users(id),
    user_id_type VARCHAR(50) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    role VARCHAR(255) NOT NULL,
    PRIMARY KEY (internal_user_id, user_id_type, user_id, role)
);
