CREATE TABLE user_identities (
    internal_user_id BIGINT NOT NULL REFERENCES users(id),
    user_id_type VARCHAR(50) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    user_name VARCHAR(255),
    active BOOLEAN DEFAULT true,
    PRIMARY KEY (internal_user_id, user_id_type)
);

CREATE UNIQUE INDEX idx_user_identities_type_user_id ON user_identities(user_id_type, user_id);
