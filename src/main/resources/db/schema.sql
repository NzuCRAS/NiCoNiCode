CREATE DATABASE IF NOT EXISTS niconicode DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE niconicode;

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `email` VARCHAR(128) NOT NULL UNIQUE,
    `password` VARCHAR(255) NOT NULL,
    `nickname` VARCHAR(64),
    `avatar` VARCHAR(255),
    `role` ENUM('USER','ADMIN') DEFAULT 'USER',
    `status` ENUM('ACTIVE','DISABLED') DEFAULT 'ACTIVE',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 会话表
CREATE TABLE IF NOT EXISTS `chat_session` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `title` VARCHAR(255),
    `summary` TEXT COMMENT '自动摘要（滑动窗口压缩后的上下文）',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 消息表
CREATE TABLE IF NOT EXISTS `chat_message` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `session_id` BIGINT NOT NULL,
    `role` ENUM('USER','ASSISTANT','SYSTEM') NOT NULL,
    `content` TEXT NOT NULL,
    `token_count` INT DEFAULT 0,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 知识文档表
CREATE TABLE IF NOT EXISTS `knowledge_doc` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `title` VARCHAR(255) NOT NULL,
    `content` TEXT NOT NULL,
    `source_type` ENUM('CHAT_SUMMARY','TRACKER_REPORT','MANUAL') NOT NULL,
    `source_id` BIGINT COMMENT '关联的 session_id 或 report_id',
    `tags` VARCHAR(512) COMMENT '逗号分隔标签',
    `view_count` INT DEFAULT 0,
    `status` ENUM('ACTIVE','ARCHIVED') DEFAULT 'ACTIVE',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FULLTEXT INDEX `ft_title_content` (`title`, `content`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 追踪技术名单
CREATE TABLE IF NOT EXISTS `tracked_tech` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name` VARCHAR(128) NOT NULL UNIQUE,
    `category` VARCHAR(64) COMMENT '框架/中间件/语言',
    `github_repo` VARCHAR(255) COMMENT 'owner/repo',
    `official_url` VARCHAR(255),
    `rss_url` VARCHAR(255),
    `sitemap_url` VARCHAR(255),
    `last_known_version` VARCHAR(64),
    `last_checked_at` DATETIME,
    `mention_count` INT DEFAULT 0 COMMENT 'AI对话中被提及次数',
    `is_hot` BOOLEAN DEFAULT FALSE,
    `status` ENUM('ACTIVE','PAUSED') DEFAULT 'ACTIVE',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 技术报道
CREATE TABLE IF NOT EXISTS `tech_report` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tracked_tech_id` BIGINT NOT NULL,
    `title` VARCHAR(255) NOT NULL,
    `content` TEXT NOT NULL COMMENT 'AI生成的报道 Markdown',
    `new_version` VARCHAR(64),
    `change_summary` TEXT COMMENT '更新内容摘要',
    `source_urls` TEXT COMMENT '信息来源链接 JSON数组',
    `status` ENUM('DRAFT','PUBLISHED') DEFAULT 'PUBLISHED',
    `published_at` DATETIME,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_tech_id` (`tracked_tech_id`),
    INDEX `idx_published_at` (`published_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 热点话题统计
CREATE TABLE IF NOT EXISTS `hot_topic` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `keyword` VARCHAR(128) NOT NULL,
    `mention_count` INT DEFAULT 1,
    `last_mentioned_at` DATETIME,
    `promoted_to_tracked` BOOLEAN DEFAULT FALSE,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX `uk_keyword` (`keyword`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 插入初始追踪技术
INSERT INTO `tracked_tech` (`name`, `category`, `github_repo`, `official_url`, `status`) VALUES
('Spring Boot', '框架', 'spring-projects/spring-boot', 'https://spring.io/projects/spring-boot', 'ACTIVE'),
('Vue.js', '框架', 'vuejs/core', 'https://vuejs.org', 'ACTIVE'),
('React', '框架', 'facebook/react', 'https://react.dev', 'ACTIVE'),
('Node.js', '运行时', 'nodejs/node', 'https://nodejs.org', 'ACTIVE'),
('LangChain4j', '框架', 'langchain4j/langchain4j', 'https://docs.langchain4j.dev', 'ACTIVE'),
('Docker', '工具', 'moby/moby', 'https://www.docker.com', 'ACTIVE'),
('Kubernetes', '平台', 'kubernetes/kubernetes', 'https://kubernetes.io', 'ACTIVE'),
('Rust', '语言', 'rust-lang/rust', 'https://www.rust-lang.org', 'ACTIVE')
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);

-- 分类表
CREATE TABLE IF NOT EXISTS `category` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name` VARCHAR(64) NOT NULL UNIQUE,
    `description` VARCHAR(255),
    `sort_order` INT DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 初始分类数据
INSERT INTO `category` (`name`, `description`, `sort_order`) VALUES
('框架', '前后端开发框架', 1),
('语言', '编程语言', 2),
('工具', '开发工具', 3),
('平台', '云平台与容器编排', 4),
('运行时', '语言运行时与执行环境', 5),
('数据库', '数据库与存储', 6),
('AI', '人工智能与机器学习', 7)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);

-- tech_report 添加分类字段（如果列已存在会报错，可以忽略）
ALTER TABLE `tech_report` ADD COLUMN `category_id` BIGINT DEFAULT NULL;
-- tech_report 添加更新时间字段
ALTER TABLE `tech_report` ADD COLUMN `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
-- tech_report 添加技术指数字段
ALTER TABLE `tech_report` ADD COLUMN `tech_index` INT DEFAULT 0 COMMENT '技术指数 (0-1000)';
-- knowledge_doc 添加分类字段
ALTER TABLE `knowledge_doc` ADD COLUMN `category_id` BIGINT DEFAULT NULL;

-- 勘误表
CREATE TABLE IF NOT EXISTS `errata` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `doc_type` ENUM('TECH_REPORT','KNOWLEDGE_DOC') NOT NULL,
    `doc_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `content` TEXT NOT NULL,
    `status` ENUM('PENDING','ACCEPTED','REJECTED') DEFAULT 'PENDING',
    `admin_note` VARCHAR(512),
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_doc` (`doc_type`, `doc_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
