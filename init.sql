CREATE TABLE `balance_changes` (
                                   `id` bigint NOT NULL AUTO_INCREMENT,
                                   `amount` decimal(15,2) NOT NULL,
                                   `balance_after` decimal(15,2) DEFAULT NULL,
                                   `balance_before` decimal(15,2) DEFAULT NULL,
                                   `completed_at` datetime(6) DEFAULT NULL,
                                   `created_at` datetime(6) NOT NULL,
                                   `external_id` bigint NOT NULL,
                                   `failure_reason` varchar(500) DEFAULT NULL,
                                   `related_id` varchar(50) DEFAULT NULL,
                                   `status` enum('COMPLETED','FAILED','PROCESSING') NOT NULL,
                                   `type` enum('REFUND','TRANSFER_IN','TRANSFER_OUT') NOT NULL,
                                   `user_id` varchar(50) NOT NULL,
                                   PRIMARY KEY (`id`),
                                   UNIQUE KEY `uk_external_id_type` (`external_id`,`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_balances` (
                                 `user_id` varchar(50) NOT NULL,
                                 `balance` decimal(15,2) NOT NULL,
                                 `created_at` datetime(6) NOT NULL,
                                 `version` bigint DEFAULT NULL,
                                 PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `transfers` (
                             `id` bigint NOT NULL AUTO_INCREMENT,
                             `amount` decimal(15,2) NOT NULL,
                             `cancelled_at` datetime(6) DEFAULT NULL,
                             `completed_at` datetime(6) DEFAULT NULL,
                             `created_at` datetime(6) NOT NULL,
                             `failure_reason` varchar(255) DEFAULT NULL,
                             `from_user_id` varchar(50) NOT NULL,
                             `status` enum('CANCELLED','COMPLETED','CREDIT_PROCESSING','DEBIT_FAILED','DEBIT_PROCESSING','PENDING') NOT NULL,
                             `to_user_id` varchar(50) NOT NULL,
                             `updated_at` datetime(6) DEFAULT NULL,
                             PRIMARY KEY (`id`),
                             KEY `idx_status_created_at` (`status`,`created_at`),
                             KEY `idx_from_user_created_at` (`from_user_id`,`created_at` DESC),
                             KEY `idx_to_user_created_at` (`to_user_id`,`created_at` DESC),
                             KEY `idx_status_updated_at` (`status`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;