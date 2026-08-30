-- 联盟成员变更必须与 guilds.allianceId 在同一事务中提交。
-- 本迁移只升级存储引擎并补充普通索引，不清洗、裁剪或重置任何历史数据。
SET @alliance_engine_sql = (
  SELECT IF(UPPER(`ENGINE`) = 'INNODB',
            'SELECT 1',
            'ALTER TABLE `alliance` ENGINE = InnoDB')
  FROM `information_schema`.`TABLES`
  WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'alliance'
);
PREPARE alliance_engine_statement FROM @alliance_engine_sql;
EXECUTE alliance_engine_statement;
DEALLOCATE PREPARE alliance_engine_statement;

SET @allianceguilds_engine_sql = (
  SELECT IF(UPPER(`ENGINE`) = 'INNODB',
            'SELECT 1',
            'ALTER TABLE `allianceguilds` ENGINE = InnoDB')
  FROM `information_schema`.`TABLES`
  WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'allianceguilds'
);
PREPARE allianceguilds_engine_statement FROM @allianceguilds_engine_sql;
EXECUTE allianceguilds_engine_statement;
DEALLOCATE PREPARE allianceguilds_engine_statement;

SET @idx_alliance_guild_sql = IF(
  EXISTS(
    SELECT 1 FROM `information_schema`.`STATISTICS`
    WHERE `TABLE_SCHEMA` = DATABASE()
      AND `TABLE_NAME` = 'allianceguilds'
      AND `INDEX_NAME` = 'idx_alliance_guild'
  ),
  'SELECT 1',
  'ALTER TABLE `allianceguilds` ADD INDEX `idx_alliance_guild` (`allianceid`, `guildid`)'
);
PREPARE idx_alliance_guild_statement FROM @idx_alliance_guild_sql;
EXECUTE idx_alliance_guild_statement;
DEALLOCATE PREPARE idx_alliance_guild_statement;

SET @idx_guildid_sql = IF(
  EXISTS(
    SELECT 1 FROM `information_schema`.`STATISTICS`
    WHERE `TABLE_SCHEMA` = DATABASE()
      AND `TABLE_NAME` = 'allianceguilds'
      AND `INDEX_NAME` = 'idx_guildid'
  ),
  'SELECT 1',
  'ALTER TABLE `allianceguilds` ADD INDEX `idx_guildid` (`guildid`)'
);
PREPARE idx_guildid_statement FROM @idx_guildid_sql;
EXECUTE idx_guildid_statement;
DEALLOCATE PREPARE idx_guildid_statement;

SET @idx_alliance_id_sql = IF(
  EXISTS(
    SELECT 1 FROM `information_schema`.`STATISTICS`
    WHERE `TABLE_SCHEMA` = DATABASE()
      AND `TABLE_NAME` = 'guilds'
      AND `INDEX_NAME` = 'idx_alliance_id'
  ),
  'SELECT 1',
  'ALTER TABLE `guilds` ADD INDEX `idx_alliance_id` (`allianceId`)'
);
PREPARE idx_alliance_id_statement FROM @idx_alliance_id_sql;
EXECUTE idx_alliance_id_statement;
DEALLOCATE PREPARE idx_alliance_id_statement;
