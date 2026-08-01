-- Component-tier seed for BrontesMssqlComponentSpec (testing arc Stage 1.2).
-- Reuses the shape of deployment/local/mssql/init-sql-configmap.yaml
-- (dbo.sample_orders) and adds dbo.sample_regions so the spec can exercise a
-- real JOIN + filter. Run against the kantheon_local database (created by the
-- spec) via SqlScripts.runResource — GO-batched, sqlcmd style.
IF OBJECT_ID('dbo.sample_orders', 'U') IS NULL
CREATE TABLE dbo.sample_orders (
    id          INT           NOT NULL PRIMARY KEY,
    tenant_id   NVARCHAR(64)  NOT NULL,
    region      NVARCHAR(64)  NOT NULL,
    amount      DECIMAL(18,2) NOT NULL,
    created_at  DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME()
);
GO
IF OBJECT_ID('dbo.sample_regions', 'U') IS NULL
CREATE TABLE dbo.sample_regions (
    region       NVARCHAR(64)  NOT NULL PRIMARY KEY,
    region_name  NVARCHAR(128) NOT NULL
);
GO
INSERT INTO dbo.sample_orders (id, tenant_id, region, amount) VALUES
    (1, 't-alpha', 'EU',   100.00),
    (2, 't-alpha', 'US',   250.50),
    (3, 't-beta',  'EU',    42.00),
    (4, 't-beta',  'APAC', 999.99);
GO
INSERT INTO dbo.sample_regions (region, region_name) VALUES
    ('EU',   'Europe'),
    ('US',   'United States'),
    ('APAC', 'Asia Pacific');
GO
