CREATE
DATABASE IF NOT EXISTS `shareCart_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE
`shareCart_db`;

DROP TABLE IF EXISTS `chat`;
DROP TABLE IF EXISTS `participant_items`;
DROP TABLE IF EXISTS `room_items`;
DROP TABLE IF EXISTS `room_participants`;
DROP TABLE IF EXISTS `rooms`;
DROP TABLE IF EXISTS `users`;
DROP TABLE IF EXISTS `towns`;

CREATE TABLE `towns`
(
    `id`        BIGINT          NOT NULL AUTO_INCREMENT,
    `region_id` VARCHAR(100)    NOT NULL,
    `town_name` VARCHAR(255)    NOT NULL,
    `sido`      VARCHAR(50)     NOT NULL,
    `sigungu`   VARCHAR(50)     NOT NULL,
    `emd`       VARCHAR(50)     NOT NULL,
    `latitude`  DECIMAL(13, 10) NOT NULL,
    `longitude` DECIMAL(13, 10) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `users`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `town_id`    BIGINT       NOT NULL,
    `email`      VARCHAR(100) NOT NULL,
    `name`       VARCHAR(100) NOT NULL,
    `password`   VARCHAR(100) NOT NULL,
    `phone`      VARCHAR(100)          DEFAULT NULL,
    `role`       VARCHAR(20)  NOT NULL DEFAULT 'USER',
    `created_at` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_users_email` (`email`),
    KEY          `FK_users_towns` (`town_id`),
    CONSTRAINT `FK_users_towns` FOREIGN KEY (`town_id`) REFERENCES `towns` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `rooms`
(
    `id`                   BIGINT      NOT NULL AUTO_INCREMENT,
    `host_id`              BIGINT      NOT NULL,
    `town_id`              BIGINT      NOT NULL,
    `market_name`          VARCHAR(100)         DEFAULT NULL,
    `meet_at`              TIMESTAMP NULL DEFAULT NULL,
    `status`               VARCHAR(50) NOT NULL DEFAULT 'RECRUITING',
    `receipt_url`          VARCHAR(2048)        DEFAULT NULL,
    `meet_place`           VARCHAR(100)         DEFAULT NULL,
    `max_participants`     INT         NOT NULL DEFAULT '4',
    `current_participants` INT         NOT NULL DEFAULT '1',
    `created_at`           TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY                    `FK_rooms_towns` (`town_id`),
    KEY                    `FK_rooms_users` (`host_id`),
    CONSTRAINT `FK_rooms_towns` FOREIGN KEY (`town_id`) REFERENCES `towns` (`id`),
    CONSTRAINT `FK_rooms_users` FOREIGN KEY (`host_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `room_participants`
(
    `id`                BIGINT      NOT NULL AUTO_INCREMENT,
    `room_id`           BIGINT      NOT NULL,
    `user_id`           BIGINT      NOT NULL,
    `role`              VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    `settlement_status` VARCHAR(20) NOT NULL DEFAULT 'UNPAID',
    `joined_at`         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_room_user` (`room_id`, `user_id`),
    KEY                 `FK_participants_users` (`user_id`),
    CONSTRAINT `FK_participants_rooms` FOREIGN KEY (`room_id`) REFERENCES `rooms` (`id`),
    CONSTRAINT `FK_participants_users` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `room_items`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `room_id`      BIGINT       NOT NULL,
    `item_name`    VARCHAR(100) NOT NULL,
    `ocr_price`    INT                   DEFAULT NULL,
    `actual_price` INT          NOT NULL DEFAULT '0',
    `total_qty`    INT                   DEFAULT NULL,
    `unit`         VARCHAR(20)           DEFAULT NULL,
    `step_qty`     INT                   DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY            `FK_room_items_rooms` (`room_id`),
    CONSTRAINT `FK_room_items_rooms` FOREIGN KEY (`room_id`) REFERENCES `rooms` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `participant_items`
(
    `id`             BIGINT NOT NULL AUTO_INCREMENT,
    `participant_id` BIGINT NOT NULL,
    `item_id`        BIGINT NOT NULL,
    `alloc_quantity` INT    NOT NULL DEFAULT '1',
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_participant_item` (`participant_id`, `item_id`),
    KEY              `FK_part_items_items` (`item_id`),
    CONSTRAINT `FK_part_items_items` FOREIGN KEY (`item_id`) REFERENCES `room_items` (`id`),
    CONSTRAINT `FK_part_items_participants` FOREIGN KEY (`participant_id`) REFERENCES `room_participants` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `chat`
(
    `id`         BIGINT      NOT NULL AUTO_INCREMENT,
    `room_id`    BIGINT      NOT NULL,
    `user_id`    BIGINT      NOT NULL,
    `content`    TEXT        NOT NULL,
    `type`       VARCHAR(20) NOT NULL DEFAULT 'TEXT',
    `created_at` TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY          `FK_chat_rooms` (`room_id`),
    KEY          `FK_chat_users` (`user_id`),
    CONSTRAINT `FK_chat_rooms` FOREIGN KEY (`room_id`) REFERENCES `rooms` (`id`),
    CONSTRAINT `FK_chat_users` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;