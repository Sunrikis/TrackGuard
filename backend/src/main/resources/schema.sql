IF OBJECT_ID(N'dbo.inspection_task', N'U') IS NULL
BEGIN
  CREATE TABLE dbo.inspection_task (
    id CHAR(36) NOT NULL PRIMARY KEY,
    title NVARCHAR(100) NOT NULL,
    source_name NVARCHAR(255) NOT NULL,
    media_type VARCHAR(10) NOT NULL,
    input_file NVARCHAR(400) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    progress INT NOT NULL DEFAULT 0,
    confidence FLOAT NOT NULL,
    sample_seconds FLOAT NOT NULL,
    roi_json NVARCHAR(MAX) NOT NULL,
    result_json NVARCHAR(MAX) NULL,
    error_message NVARCHAR(1000) NULL,
    created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    started_at DATETIME2 NULL,
    finished_at DATETIME2 NULL,
    elapsed_ms BIGINT NULL,
    attempt INT NOT NULL DEFAULT 1,
    CONSTRAINT ck_task_status CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    CONSTRAINT ck_task_progress CHECK (progress BETWEEN 0 AND 100)
  );
  CREATE INDEX ix_task_created ON dbo.inspection_task(created_at DESC);
END
GO
IF OBJECT_ID(N'dbo.warning_event', N'U') IS NULL
BEGIN
  CREATE TABLE dbo.warning_event (
    id CHAR(36) NOT NULL PRIMARY KEY,
    task_id CHAR(36) NOT NULL REFERENCES dbo.inspection_task(id),
    track_key VARCHAR(40) NOT NULL,
    category VARCHAR(16) NOT NULL,
    label NVARCHAR(60) NOT NULL,
    confidence FLOAT NOT NULL,
    risk VARCHAR(10) NOT NULL,
    frame_time FLOAT NOT NULL,
    snapshot NVARCHAR(400) NOT NULL,
    box_json NVARCHAR(400) NOT NULL,
    advice NVARCHAR(500) NOT NULL,
    advice_source VARCHAR(20) NOT NULL DEFAULT 'LOCAL_RULE',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    review_note NVARCHAR(1000) NULL,
    reviewed_at DATETIME2 NULL,
    created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT ck_event_category CHECK (category IN ('person','vehicle','motorcycle','animal','obstacle')),
    CONSTRAINT ck_event_status CHECK (status IN ('PENDING','PROCESSING','RESOLVED','FALSE_POSITIVE')),
    CONSTRAINT uq_event_track UNIQUE(task_id, track_key)
  );
  CREATE INDEX ix_event_created ON dbo.warning_event(created_at DESC);
  CREATE INDEX ix_event_task ON dbo.warning_event(task_id);
END
GO
IF OBJECT_ID(N'dbo.task_log', N'U') IS NULL
BEGIN
  CREATE TABLE dbo.task_log (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    task_id CHAR(36) NOT NULL REFERENCES dbo.inspection_task(id),
    level VARCHAR(10) NOT NULL,
    message NVARCHAR(1000) NOT NULL,
    created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
  );
  CREATE INDEX ix_log_task ON dbo.task_log(task_id, id);
END
GO
IF OBJECT_ID(N'dbo.app_user', N'U') IS NULL
BEGIN
  CREATE TABLE dbo.app_user (
    id CHAR(36) NOT NULL PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    display_name NVARCHAR(40) NOT NULL,
    password_hash VARCHAR(200) NOT NULL,
    avatar_file NVARCHAR(100) NULL,
    created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
  );
END
GO
IF COL_LENGTH('dbo.inspection_task', 'model_id') IS NULL
  ALTER TABLE dbo.inspection_task ADD model_id NVARCHAR(100) NOT NULL CONSTRAINT df_task_model_id DEFAULT 'trackguard-world.pt';
GO
IF COL_LENGTH('dbo.inspection_task', 'model_name') IS NULL
  ALTER TABLE dbo.inspection_task ADD model_name NVARCHAR(100) NOT NULL CONSTRAINT df_task_model_name DEFAULT 'trackguard-world.pt';
GO
IF COL_LENGTH('dbo.inspection_task', 'target_categories') IS NULL
  ALTER TABLE dbo.inspection_task ADD target_categories NVARCHAR(200) NOT NULL CONSTRAINT df_task_targets DEFAULT '["person","vehicle","motorcycle","animal","obstacle"]';
GO
IF COL_LENGTH('dbo.warning_event', 'advice_source') IS NULL
  ALTER TABLE dbo.warning_event ADD advice_source VARCHAR(20) NOT NULL CONSTRAINT df_event_advice_source DEFAULT 'LOCAL_RULE';
GO
-- Upgrade existing installations without rewriting historical detections.
IF HAS_PERMS_BY_NAME(N'dbo.warning_event', N'OBJECT', N'ALTER') = 1
AND EXISTS (SELECT 1 FROM sys.check_constraints WHERE parent_object_id = OBJECT_ID(N'dbo.warning_event') AND name = N'ck_event_category' AND COALESCE(definition, '') NOT LIKE '%motorcycle%')
BEGIN
  ALTER TABLE dbo.warning_event DROP CONSTRAINT ck_event_category;
  ALTER TABLE dbo.warning_event WITH CHECK ADD CONSTRAINT ck_event_category CHECK (category IN ('person','vehicle','motorcycle','animal','obstacle'));
END
GO
IF HAS_PERMS_BY_NAME(N'dbo.inspection_task', N'OBJECT', N'ALTER') = 1
AND EXISTS (SELECT 1 FROM sys.default_constraints WHERE parent_object_id = OBJECT_ID(N'dbo.inspection_task') AND name = N'df_task_targets' AND COALESCE(definition, '') NOT LIKE '%motorcycle%')
BEGIN
  ALTER TABLE dbo.inspection_task DROP CONSTRAINT df_task_targets;
  ALTER TABLE dbo.inspection_task ADD CONSTRAINT df_task_targets DEFAULT '["person","vehicle","motorcycle","animal","obstacle"]' FOR target_categories;
END
GO
