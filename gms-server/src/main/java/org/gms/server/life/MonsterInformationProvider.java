/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 ~ 2010 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License version 3
 as published by the Free Software Foundation. You may not use, modify
 or distribute this program under any other version of the
 GNU Affero General Public License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.gms.server.life;

import org.gms.config.GameConfig;
import org.gms.constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.provider.Data;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;
import org.gms.server.ItemInformationProvider;
import org.gms.util.DatabaseConnection;
import org.gms.util.I18nUtil;
import org.gms.util.Pair;
import org.gms.util.Randomizer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class MonsterInformationProvider {
    private static final Logger log = LoggerFactory.getLogger(MonsterInformationProvider.class);
    // Author : LightPepsi

    private static final MonsterInformationProvider instance = new MonsterInformationProvider();

    public static MonsterInformationProvider getInstance() {
        return instance;
    }

    private volatile DropCache dropCache = new DropCache();
    private final GlobalDropStore globalDropStore = new GlobalDropStore();

    /**
     *  Pair<Integer, Integer>
     *      Integer: 怪物ID
     *      Integer: 攻击类型
     *  Integer: 统计攻击动作总时长
     * **/
    private final Map<Pair<Integer, Integer>, Integer> mobAttackAnimationTime = new HashMap<>();
    /**
     *  怪物技能
     *  MobSkill: 怪物技能类型和等级
     *  Integer: 技能播放总时长
     * **/
    private final Map<MobSkill, Integer> mobSkillAnimationTime = new HashMap<>();

    /**
     *  同 mobSkillAnimationTime
     *  添加函数 setMobAttackInfo()
     *  Integer: (怪物ID << 3) + 攻击类型索引
     *  Pair<Integer, Integer>:
     *      Integer: 怪物攻击动作所消耗的MP
     *      Integer: 出招后的冷却/攻击间隔
     * **/
    private final Map<Integer, Pair<Integer, Integer>> mobAttackInfo = new HashMap<>();

    private final Map<Integer, Boolean> mobBossCache = new HashMap<>();
    private final Map<Integer, String> mobNameCache = new HashMap<>();

    protected MonsterInformationProvider() {
        reloadGlobalDrops();
    }

    public final List<MonsterGlobalDropEntry> getRelevantGlobalDrops(int mapid) {
        return globalDropStore.getRelevantDrops(mapid);
    }

    private List<MonsterGlobalDropEntry> loadGlobalDrops() throws SQLException {
        List<MonsterGlobalDropEntry> loadedDrops = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT * FROM drop_data_global WHERE chance > 0");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                loadedDrops.add(new MonsterGlobalDropEntry(
                        rs.getInt("itemid"),
                        rs.getInt("chance"),
                        rs.getByte("continent"),
                        rs.getInt("minimum_quantity"),
                        rs.getInt("maximum_quantity"),
                        rs.getShort("questid")));
            }
        }
        return loadedDrops;
    }

    private void reloadGlobalDrops() {
        try {
            globalDropStore.replaceAll(loadGlobalDrops());
        } catch (SQLException e) {
            log.error(I18nUtil.getLogMessage("MonsterInformationProvider.retrieveGlobal.error1"), e);
        }
    }

    public List<MonsterDropEntry> retrieveEffectiveDrop(final int monsterId) {
        // this reads the drop entries searching for multi-equip, properly processing them

        DropCache cache = dropCache;
        List<MonsterDropEntry> list = retrieveDrop(cache, monsterId);
        if (list == null) {
            return List.of();
        }
        if (cache.hasNoMultiEquipDrops.contains(monsterId) || !GameConfig.getServerBoolean("use_multiple_same_equip_drop")) {
            return list;
        }

        List<MonsterDropEntry> multiDrops = cache.extraMultiEquipDrops.get(monsterId), extra = new LinkedList<>();
        if (multiDrops == null) {
            multiDrops = new LinkedList<>();

            for (MonsterDropEntry mde : list) {
                if (ItemConstants.isEquipment(mde.itemId) && mde.Maximum > 1) {
                    multiDrops.add(mde);

                    int rnd = Randomizer.rand(mde.Minimum, mde.Maximum);
                    for (int i = 0; i < rnd - 1; i++) {
                        extra.add(mde);   // this passes copies of the equips' MDE with min/max quantity > 1, but idc on equips they are unused anyways
                    }
                }
            }

            if (!multiDrops.isEmpty()) {
                cache.extraMultiEquipDrops.put(monsterId, List.copyOf(multiDrops));
            } else {
                cache.hasNoMultiEquipDrops.add(monsterId);
            }
        } else {
            for (MonsterDropEntry mde : multiDrops) {
                int rnd = Randomizer.rand(mde.Minimum, mde.Maximum);
                for (int i = 0; i < rnd - 1; i++) {
                    extra.add(mde);
                }
            }
        }

        List<MonsterDropEntry> ret = new LinkedList<>(list);
        ret.addAll(extra);

        return ret;
    }

    public final List<MonsterDropEntry> retrieveDrop(final int monsterId) {
        List<MonsterDropEntry> loadedDrops = retrieveDrop(dropCache, monsterId);
        return loadedDrops == null ? List.of() : loadedDrops;
    }

    private List<MonsterDropEntry> retrieveDrop(DropCache cache, int monsterId) {
        return cache.drops.computeIfAbsent(monsterId, this::loadMonsterDrops);
    }

    private List<MonsterDropEntry> loadMonsterDrops(int monsterId) {
        List<MonsterDropEntry> loadedDrops = new ArrayList<>();

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT itemid, chance, minimum_quantity, maximum_quantity, questid FROM drop_data WHERE dropperid = ?")) {
            ps.setInt(1, monsterId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    loadedDrops.add(new MonsterDropEntry(rs.getInt("itemid"), rs.getInt("chance"), rs.getInt("minimum_quantity"), rs.getInt("maximum_quantity"), rs.getShort("questid")));
                }
            }
        } catch (SQLException e) {
            log.error(I18nUtil.getLogMessage("MonsterInformationProvider.retrieveDrop.error1"), monsterId, e);
            return null;
        }

        return List.copyOf(loadedDrops);
    }

    public final MonsterDropEntry retrieveRandomStealDrop(int monsterId) {
        DropCache cache = dropCache;
        List<Integer> dropPool = retrieveDropPool(cache, monsterId);
        if (dropPool == null || dropPool.isEmpty()) {
            return null;
        }

        int randomValue = (int) Math.floor(Math.random() * dropPool.getLast());
        int index = 0;
        while (randomValue >= dropPool.get(index)) {
            index++;
        }

        List<MonsterDropEntry> drops = retrieveDrop(cache, monsterId);
        return drops == null || index >= drops.size() ? null : drops.get(index);
    }

    private List<Integer> retrieveDropPool(DropCache cache, int monsterId) {
        return cache.dropsChancePool.computeIfAbsent(monsterId, id -> buildDropPool(cache, id));
    }

    private List<Integer> buildDropPool(DropCache cache, int monsterId) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        List<MonsterDropEntry> dropList = retrieveDrop(cache, monsterId);
        if (dropList == null) {
            return null;
        }
        List<Integer> ret = new ArrayList<>();

        int accProp = 0;
        for (MonsterDropEntry mde : dropList) {
            if (
                  GameConfig.getServerBoolean("allow_steal_quest_item") ||
                  !ii.isQuestItem(mde.itemId) && !ii.isPartyQuestItem(mde.itemId)
            ) {
                accProp += mde.chance;
            }
            ret.add(accProp);
        }

        if (accProp == 0) {
            ret.clear();    // don't accept mobs dropping no relevant items
        }
        return List.copyOf(ret);
    }

    public final void setMobAttackAnimationTime(int monsterId, int attackPos, int animationTime) {
        mobAttackAnimationTime.put(new Pair<>(monsterId, attackPos), animationTime);
    }

    public final Integer getMobAttackAnimationTime(int monsterId, int attackPos) {
        Integer time = mobAttackAnimationTime.get(new Pair<>(monsterId, attackPos));
        return time == null ? 0 : time;
    }

    public final void setMobSkillAnimationTime(MobSkill skill, int animationTime) {
        mobSkillAnimationTime.put(skill, animationTime);
    }

    public final Integer getMobSkillAnimationTime(MobSkill skill) {
        Integer time = mobSkillAnimationTime.get(skill);
        return time == null ? 0 : time;
    }

    public final void setMobAttackInfo(int monsterId, int attackPos, int mpCon, int coolTime) {
        mobAttackInfo.put((monsterId << 3) + attackPos, new Pair<>(mpCon, coolTime));
    }

    public final Pair<Integer, Integer> getMobAttackInfo(int monsterId, int attackPos) {
        if (attackPos < 0 || attackPos > 7) {
            return null;
        }
        return mobAttackInfo.get((monsterId << 3) + attackPos);
    }

    public static ArrayList<Pair<Integer, String>> getMobsIDsFromName(String search) {
        DataProvider dataProvider = DataProviderFactory.getDataProvider(WZFiles.STRING);
        ArrayList<Pair<Integer, String>> retMobs = new ArrayList<>();
        Data data = dataProvider.getData("Mob.img");
        List<Pair<Integer, String>> mobPairList = new LinkedList<>();
        for (Data mobIdData : data.getChildren()) {
            int mobIdFromData = Integer.parseInt(mobIdData.getName());
            String mobNameFromData = DataTool.getString(mobIdData.getChildByPath("name"), "NO-NAME");
            mobPairList.add(new Pair<>(mobIdFromData, mobNameFromData));
        }
        for (Pair<Integer, String> mobPair : mobPairList) {
            if (mobPair.getRight().toLowerCase().contains(search.toLowerCase())) {
                retMobs.add(mobPair);
            }
        }
        return retMobs;
    }

    public boolean isBoss(int id) {
        Boolean boss = mobBossCache.get(id);
        if (boss == null) {
            try {
                boss = LifeFactory.getMonster(id).isBoss();
            } catch (NullPointerException npe) {
                boss = false;
            } catch (Exception e) {   //nonexistant mob
                boss = false;

                log.warn("Non-existent mob id {}", id, e);
            }

            mobBossCache.put(id, boss);
        }

        return boss;
    }

    public String getMobNameFromId(int id) {
        String mobName = mobNameCache.get(id);
        if (mobName == null) {
            DataProvider dataProvider = DataProviderFactory.getDataProvider(WZFiles.STRING);
            Data mobData = dataProvider.getData("Mob.img");

            mobName = DataTool.getString(mobData.getChildByPath(id + "/name"), "");
            mobNameCache.put(id, mobName);
        }

        return mobName;
    }

    public final synchronized void clearDrops() {
        reloadGlobalDrops();
        dropCache = new DropCache();
    }

    private static final class DropCache {
        private final ConcurrentMap<Integer, List<MonsterDropEntry>> drops = new ConcurrentHashMap<>();
        private final ConcurrentMap<Integer, List<Integer>> dropsChancePool = new ConcurrentHashMap<>();
        private final Set<Integer> hasNoMultiEquipDrops = ConcurrentHashMap.newKeySet();
        private final ConcurrentMap<Integer, List<MonsterDropEntry>> extraMultiEquipDrops = new ConcurrentHashMap<>();
    }

    private static final class GlobalDropStore {
        private volatile GlobalDropSnapshot snapshot = GlobalDropSnapshot.empty();

        private List<MonsterGlobalDropEntry> getRelevantDrops(int mapId) {
            return snapshot.getRelevantDrops(mapId);
        }

        private void replaceAll(List<MonsterGlobalDropEntry> drops) {
            snapshot = new GlobalDropSnapshot(drops);
        }
    }

    private static final class GlobalDropSnapshot {
        private final List<MonsterGlobalDropEntry> allDrops;
        private final ConcurrentMap<Integer, List<MonsterGlobalDropEntry>> continentDrops = new ConcurrentHashMap<>();

        private GlobalDropSnapshot(List<MonsterGlobalDropEntry> drops) {
            allDrops = List.copyOf(drops);
        }

        private static GlobalDropSnapshot empty() {
            return new GlobalDropSnapshot(List.of());
        }

        private List<MonsterGlobalDropEntry> getRelevantDrops(int mapId) {
            int continentId = mapId / 100000000;
            return continentDrops.computeIfAbsent(continentId, this::loadContinentDrops);
        }

        private List<MonsterGlobalDropEntry> loadContinentDrops(int continentId) {
            return allDrops.stream()
                    .filter(drop -> drop.continentid < 0 || drop.continentid == continentId)
                    .toList();
        }
    }
}
