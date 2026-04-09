CREATE TABLE client (
                        id          BIGSERIAL PRIMARY KEY,
                        first_name  VARCHAR(100) NOT NULL,
                        last_name   VARCHAR(100) NOT NULL,
                        phone       VARCHAR(50) NOT NULL UNIQUE
);