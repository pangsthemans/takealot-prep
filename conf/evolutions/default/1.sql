# --- !Ups

CREATE TABLE parcels (
    id                BIGSERIAL    PRIMARY KEY,
    sender_name       VARCHAR(255) NOT NULL,
    recipient_name    VARCHAR(255) NOT NULL,
    recipient_address TEXT         NOT NULL,
    current_status    VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

# --- !Downs

DROP TABLE parcels;
