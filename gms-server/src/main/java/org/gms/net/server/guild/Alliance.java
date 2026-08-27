/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package org.gms.net.server.guild;

import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.packet.Packet;
import org.gms.net.server.Server;
import org.gms.net.server.coordinator.world.InviteCoordinator;
import org.gms.net.server.coordinator.world.InviteCoordinator.InviteResult;
import org.gms.net.server.coordinator.world.InviteCoordinator.InviteType;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.util.DatabaseConnection;
import org.gms.util.I18nUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

/**
 * @author XoticStory
 * @author Ronan
 */
@Slf4j
public class Alliance {
    final private List<Integer> guilds = new LinkedList<>();

    public enum GuildRemovalResult {
        SUCCESS,
        LEADER_GUILD,
        FAILED
    }

    private int allianceId = -1;
    private int capacity;
    private String name;
    private String notice = "";
    private String[] rankTitles = new String[5];

    public Alliance(String name, int id) {
        this.name = name;
        allianceId = id;
        String[] ranks = {"Master", "Jr. Master", "Member", "Member", "Member"};
        for (int i = 0; i < 5; i++) {
            rankTitles[i] = ranks[i];
        }
    }

    public static boolean canBeUsedAllianceName(String name) {
        if (name.contains(" ") || name.length() > 12) {
            return false;
        }

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT name FROM alliance WHERE name = ?")) {
            ps.setString(1, name);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static List<Character> getPartyGuildMasters(Party party) {
        List<Character> mcl = new LinkedList<>();

        for (PartyCharacter mpc : party.getMembers()) {
            Character chr = mpc.getPlayer();
            if (chr != null) {
                Character lchr = party.getLeader().getPlayer();
                if (chr.getGuildRank() == 1 && lchr != null && chr.getMapId() == lchr.getMapId()) {
                    mcl.add(chr);
                }
            }
        }

        if (!mcl.isEmpty() && !mcl.get(0).isPartyLeader()) {
            for (int i = 1; i < mcl.size(); i++) {
                if (mcl.get(i).isPartyLeader()) {
                    Character temp = mcl.get(0);
                    mcl.set(0, mcl.get(i));
                    mcl.set(i, temp);
                }
            }
        }

        return mcl;
    }

    public static synchronized Alliance createAlliance(Party party, String name, int creationCost) {
        if (party == null || name == null || name.contains(" ") ||
                name.length() > 12 || creationCost <= 0) {
            return null;
        }
        List<Character> guildMasters = getPartyGuildMasters(party);
        if (guildMasters.size() != 2) {
            return null;
        }

        List<Integer> guilds = new LinkedList<>();
        for (Character mc : guildMasters) {
            guilds.add(mc.getGuildId());
        }
        Character payer = guildMasters.get(0);
        int[] allianceId = {-1};
        boolean purchased = payer.spendMesoTransactionally(creationCost, balanceAfter -> {
            allianceId[0] = createAllianceOnDb(guildMasters, guilds, name, payer.getId(), balanceAfter);
            return allianceId[0] > 0;
        });
        if (!purchased) {
            return null;
        }

        Alliance alliance = new Alliance(name, allianceId[0]);
        try {
            alliance.setCapacity(guilds.size());
            for (Integer guildId : guilds) {
                alliance.addGuild(guildId);
            }

            Server server = Server.getInstance();
            server.addAlliance(alliance.getId(), alliance);
            for (int i = 0; i < guildMasters.size(); i++) {
                int guildId = guilds.get(i);
                server.setGuildAllianceIdInMemory(guildId, alliance.getId());
                server.resetAllianceGuildPlayersRankInMemory(guildId);

                Character chr = guildMasters.get(i);
                int allianceRank = i == 0 ? 1 : 2;
                chr.getMGC().setAllianceRank(allianceRank);
                Guild guild = server.getGuild(chr.getGuildId());
                if (guild != null && guild.getMGC(chr.getId()) != null) {
                    guild.getMGC(chr.getId()).setAllianceRank(allianceRank);
                }
            }

            int worldId = payer.getWorld();
            server.allianceMessage(alliance.getId(), GuildPackets.updateAllianceInfo(alliance, worldId), -1, -1);
            server.allianceMessage(alliance.getId(), GuildPackets.getGuildAlliances(alliance, worldId), -1, -1);
        } catch (RuntimeException e) {
            log.error(I18nUtil.getLogMessage("Alliance.runtime.error1"),
                    "Alliance.create", alliance.getId(), e);
        }
        return alliance;
    }

    private static int createAllianceOnDb(List<Character> guildMasters, List<Integer> guildIds,
                                          String name, int payerId, int balanceAfter) {
        if (guildMasters.size() != 2 || guildIds.size() != 2 ||
                guildIds.get(0).equals(guildIds.get(1))) {
            return -1;
        }

        Character firstGuildMaster = guildMasters.get(0);
        Character secondGuildMaster = guildMasters.get(1);
        int firstGuildId = guildIds.get(0);
        int secondGuildId = guildIds.get(1);
        try (Connection con = DatabaseConnection.getConnection()) {
            boolean previousAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                int allianceId;
                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO alliance (name, capacity) " +
                                "SELECT ?, ? WHERE NOT EXISTS " +
                                "(SELECT 1 FROM alliance WHERE name = ?)",
                        PreparedStatement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, name);
                    ps.setInt(2, guildIds.size());
                    ps.setString(3, name);
                    if (ps.executeUpdate() != 1) {
                        throw new SQLException("Alliance name already exists: " + name);
                    }
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (!rs.next()) {
                            throw new SQLException("Alliance id was not generated");
                        }
                        allianceId = rs.getInt(1);
                    }
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT g.guildid, g.allianceId, g.leader, c.id AS leaderCharacterId " +
                                "FROM guilds g LEFT JOIN characters c " +
                                "ON c.id = g.leader AND c.guildid = g.guildid " +
                                "WHERE g.guildid IN (?, ?) ORDER BY g.guildid FOR UPDATE")) {
                    ps.setInt(1, firstGuildId);
                    ps.setInt(2, secondGuildId);
                    int lockedGuilds = 0;
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            int guildId = rs.getInt("guildid");
                            int expectedLeaderId = guildId == firstGuildId
                                    ? firstGuildMaster.getId() : secondGuildMaster.getId();
                            if (rs.getInt("allianceId") != 0 ||
                                    rs.getInt("leader") != expectedLeaderId ||
                                    rs.getObject("leaderCharacterId") == null) {
                                throw new SQLException("Guild alliance state changed: " + guildId);
                            }
                            lockedGuilds++;
                        }
                    }
                    if (lockedGuilds != guildIds.size()) {
                        throw new SQLException("Alliance guild state changed: " + guildIds);
                    }
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "DELETE FROM allianceguilds WHERE guildid IN (?, ?)")) {
                    ps.setInt(1, firstGuildId);
                    ps.setInt(2, secondGuildId);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE guilds SET allianceId = ? " +
                                "WHERE guildid IN (?, ?) AND allianceId = 0")) {
                    ps.setInt(1, allianceId);
                    ps.setInt(2, firstGuildId);
                    ps.setInt(3, secondGuildId);
                    if (ps.executeUpdate() != guildIds.size()) {
                        throw new SQLException("Alliance guild state changed: " + guildIds);
                    }
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO allianceguilds (allianceid, guildid) " +
                                "VALUES (?, ?), (?, ?)")) {
                    ps.setInt(1, allianceId);
                    ps.setInt(2, firstGuildId);
                    ps.setInt(3, allianceId);
                    ps.setInt(4, secondGuildId);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE characters c JOIN (" +
                                "SELECT ? AS guildid, ? AS leaderid, 1 AS leaderRank " +
                                "UNION ALL SELECT ?, ?, 2" +
                                ") target ON target.guildid = c.guildid " +
                                "SET c.allianceRank = IF(c.id = target.leaderid, " +
                                "target.leaderRank, 5) " +
                                "WHERE c.allianceRank <> IF(c.id = target.leaderid, " +
                                "target.leaderRank, 5)")) {
                    ps.setInt(1, firstGuildId);
                    ps.setInt(2, firstGuildMaster.getId());
                    ps.setInt(3, secondGuildId);
                    ps.setInt(4, secondGuildMaster.getId());
                    ps.executeUpdate();
                }

                updateCharacterMeso(con, payerId, balanceAfter);
                con.commit();
                return allianceId;
            } catch (Exception e) {
                rollback(con, e);
                log.error(I18nUtil.getLogMessage("Alliance.create.error1"), name, guildIds, e);
                return -1;
            } finally {
                restoreAutoCommit(con, previousAutoCommit, "Alliance.create");
            }
        } catch (Exception e) {
            log.error(I18nUtil.getLogMessage("Alliance.create.error1"), name, guildIds, e);
            return -1;
        }
    }

    public static Alliance loadAlliance(int id) {
        if (id <= 0) {
            return null;
        }
        Alliance alliance = new Alliance(null, -1);
        try (Connection con = DatabaseConnection.getConnection()) {

            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM alliance WHERE id = ?")) {
                ps.setInt(1, id);

                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }

                    alliance.allianceId = id;
                    alliance.capacity = rs.getInt("capacity");
                    alliance.name = rs.getString("name");
                    alliance.notice = rs.getString("notice");

                    String[] ranks = new String[5];
                    ranks[0] = rs.getString("rank1");
                    ranks[1] = rs.getString("rank2");
                    ranks[2] = rs.getString("rank3");
                    ranks[3] = rs.getString("rank4");
                    ranks[4] = rs.getString("rank5");
                    alliance.rankTitles = ranks;
                }
            }

            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT ag.guildid FROM allianceguilds ag " +
                            "JOIN guilds g ON g.guildid = ag.guildid " +
                            "AND g.allianceId = ag.allianceid " +
                            "WHERE ag.allianceid = ? " +
                            "GROUP BY ag.guildid ORDER BY MIN(ag.id)")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int guildId = rs.getInt("guildid");
                        if (alliance.addLoadedGuild(guildId) &&
                                alliance.getGuilds().size() > alliance.capacity) {
                            log.warn(I18nUtil.getLogMessage("Alliance.load.error1"),
                                    id, alliance.capacity, guildId);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error(I18nUtil.getLogMessage("Alliance.load.error2"), id, e);
            return null;
        }

        return alliance;
    }

    public synchronized boolean updateRankTitles(String[] ranks) {
        if (ranks == null || ranks.length != rankTitles.length) {
            return false;
        }

        List<Integer> changedRanks = new LinkedList<>();
        StringBuilder sql = new StringBuilder("UPDATE alliance SET ");
        for (int i = 0; i < ranks.length; i++) {
            if (!Objects.equals(rankTitles[i], ranks[i])) {
                if (!changedRanks.isEmpty()) {
                    sql.append(", ");
                }
                sql.append("rank").append(i + 1).append(" = ?");
                changedRanks.add(i);
            }
        }
        if (changedRanks.isEmpty()) {
            return true;
        }
        sql.append(" WHERE id = ?");

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {
            int parameterIndex = 1;
            for (Integer rankIndex : changedRanks) {
                ps.setString(parameterIndex++, ranks[rankIndex]);
            }
            ps.setInt(parameterIndex, allianceId);
            if (ps.executeUpdate() != 1) {
                return false;
            }
            rankTitles = ranks.clone();
            return true;
        } catch (Exception e) {
            log.error(I18nUtil.getLogMessage("Alliance.save.error1"), allianceId, e);
            return false;
        }
    }

    public synchronized boolean updateNotice(String newNotice) {
        if (Objects.equals(notice, newNotice)) {
            return true;
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE alliance SET notice = ? WHERE id = ?")) {
            ps.setString(1, newNotice);
            ps.setInt(2, allianceId);
            if (ps.executeUpdate() != 1) {
                return false;
            }
            notice = newNotice;
            return true;
        } catch (Exception e) {
            log.error(I18nUtil.getLogMessage("Alliance.save.error1"), allianceId, e);
            return false;
        }
    }

    public synchronized boolean changeLeader(Character currentLeader, Character newLeader) {
        if (currentLeader == null || currentLeader.getMGC() == null ||
                currentLeader.getAllianceRank() != 1 || currentLeader.getGuildRank() != 1 ||
                newLeader == null || newLeader.getMGC() == null || newLeader.getGuildId() < 1 ||
                newLeader.getGuildRank() != 1 || newLeader.getAllianceRank() != 2) {
            return false;
        }

        int[] previousLeader = {-1, -1};
        try (Connection con = DatabaseConnection.getConnection()) {
            boolean previousAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT id FROM alliance WHERE id = ? FOR UPDATE")) {
                    ps.setInt(1, allianceId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("Alliance does not exist: " + allianceId);
                        }
                    }
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT DISTINCT c.id, c.guildid FROM characters c " +
                                "JOIN guilds g ON g.guildid = c.guildid " +
                                "JOIN allianceguilds ag ON ag.guildid = g.guildid " +
                                "AND ag.allianceid = g.allianceId " +
                                "WHERE g.allianceId = ? AND c.id = g.leader " +
                                "AND c.allianceRank = 1")) {
                    ps.setInt(1, allianceId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("Alliance leader does not exist: " + allianceId);
                        }
                        previousLeader[0] = rs.getInt("id");
                        previousLeader[1] = rs.getInt("guildid");
                        if (rs.next()) {
                            throw new SQLException("Alliance has multiple leaders: " + allianceId);
                        }
                        if (previousLeader[0] != currentLeader.getId()) {
                            throw new SQLException("Alliance leader authorization changed: " +
                                    currentLeader.getId());
                        }
                    }
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE characters SET allianceRank = 2 " +
                                "WHERE id = ? AND guildid = ? AND allianceRank = 1")) {
                    ps.setInt(1, previousLeader[0]);
                    ps.setInt(2, previousLeader[1]);
                    if (ps.executeUpdate() != 1) {
                        throw new SQLException("Alliance leader state changed: " + previousLeader[0]);
                    }
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE characters c JOIN guilds g ON g.guildid = c.guildid " +
                                "JOIN allianceguilds ag ON ag.guildid = g.guildid " +
                                "AND ag.allianceid = g.allianceId " +
                                "SET c.allianceRank = 1 WHERE c.id = ? AND c.guildid = ? " +
                                "AND c.guildrank = 1 AND c.allianceRank = 2 " +
                                "AND g.allianceId = ?")) {
                    ps.setInt(1, newLeader.getId());
                    ps.setInt(2, newLeader.getGuildId());
                    ps.setInt(3, allianceId);
                    if (ps.executeUpdate() != 1) {
                        throw new SQLException("New alliance leader state changed: " + newLeader.getId());
                    }
                }

                con.commit();
            } catch (Exception e) {
                rollback(con, e);
                log.error(I18nUtil.getLogMessage("Alliance.leader.error1"),
                        allianceId, newLeader.getId(), e);
                return false;
            } finally {
                restoreAutoCommit(con, previousAutoCommit, "Alliance.changeLeader");
            }
        } catch (Exception e) {
            log.error(I18nUtil.getLogMessage("Alliance.leader.error1"),
                    allianceId, newLeader.getId(), e);
            return false;
        }

        try {
            newLeader.getMGC().setAllianceRank(1);
            Guild oldLeaderGuild = Server.getInstance().getGuild(previousLeader[1]);
            if (oldLeaderGuild != null) {
                GuildCharacter oldLeader = oldLeaderGuild.getMGC(previousLeader[0]);
                if (oldLeader != null) {
                    oldLeader.setOfflineAllianceRank(2);
                    if (oldLeader.getCharacter() != null) {
                        oldLeader.getCharacter().setAllianceRank(2);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.error(I18nUtil.getLogMessage("Alliance.runtime.error1"),
                    "Alliance.changeLeader", allianceId, e);
        }
        return true;
    }

    public static boolean isAllianceMissing(int allianceId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT 1 FROM alliance WHERE id = ? LIMIT 1")) {
            ps.setInt(1, allianceId);
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        } catch (Exception e) {
            log.error(I18nUtil.getLogMessage("Alliance.load.error2"), allianceId, e);
            return false;
        }
    }

    public synchronized boolean addGuildAndSave(int guildId, int guildMasterId) {
        synchronized (guilds) {
            if (guilds.size() >= capacity || getGuildIndex(guildId) > -1) {
                return false;
            }
        }
        if (!saveGuildJoin(guildId, guildMasterId)) {
            return false;
        }
        synchronized (guilds) {
            guilds.add(guildId);
        }
        return true;
    }

    public synchronized boolean removeGuildAndSave(int guildId) {
        synchronized (guilds) {
            if (getGuildIndex(guildId) == -1) {
                return false;
            }
        }
        if (!saveGuildRemoval(guildId)) {
            return false;
        }
        synchronized (guilds) {
            guilds.remove(Integer.valueOf(guildId));
        }
        return true;
    }

    private boolean saveGuildJoin(int guildId, int guildMasterId) {
        try (Connection con = DatabaseConnection.getConnection()) {
            boolean previousAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                int persistedCapacity;
                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT capacity FROM alliance WHERE id = ? FOR UPDATE")) {
                    ps.setInt(1, allianceId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("Alliance does not exist: " + allianceId);
                        }
                        persistedCapacity = rs.getInt("capacity");
                    }
                }
                if (persistedCapacity != capacity) {
                    throw new SQLException("Alliance capacity changed: " + allianceId);
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT COUNT(DISTINCT ag.guildid) FROM allianceguilds ag " +
                                "JOIN guilds g ON g.guildid = ag.guildid " +
                                "AND g.allianceId = ag.allianceid " +
                                "WHERE ag.allianceid = ?")) {
                    ps.setInt(1, allianceId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getInt(1) >= persistedCapacity) {
                            throw new SQLException("Alliance capacity is full: " + allianceId);
                        }
                    }
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE guilds g " +
                                "JOIN characters c ON c.id = g.leader AND c.guildid = g.guildid " +
                                "SET g.allianceId = ? " +
                                "WHERE g.guildid = ? AND g.allianceId = 0 AND g.leader = ?")) {
                    ps.setInt(1, allianceId);
                    ps.setInt(2, guildId);
                    ps.setInt(3, guildMasterId);
                    if (ps.executeUpdate() != 1) {
                        throw new SQLException("Guild or guild master state changed: " + guildId);
                    }
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "DELETE FROM allianceguilds WHERE guildid = ?")) {
                    ps.setInt(1, guildId);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO allianceguilds (allianceid, guildid) VALUES (?, ?)")) {
                    ps.setInt(1, allianceId);
                    ps.setInt(2, guildId);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE characters SET allianceRank = IF(id = ?, 2, 5) " +
                                "WHERE guildid = ? AND allianceRank <> IF(id = ?, 2, 5)")) {
                    ps.setInt(1, guildMasterId);
                    ps.setInt(2, guildId);
                    ps.setInt(3, guildMasterId);
                    ps.executeUpdate();
                }

                con.commit();
                return true;
            } catch (Exception e) {
                rollback(con, e);
                log.error(I18nUtil.getLogMessage("Alliance.member.error1"), allianceId, guildId, e);
                return false;
            } finally {
                restoreAutoCommit(con, previousAutoCommit, "Alliance.addGuild");
            }
        } catch (Exception e) {
            log.error(I18nUtil.getLogMessage("Alliance.member.error1"), allianceId, guildId, e);
            return false;
        }
    }

    private boolean saveGuildRemoval(int guildId) {
        try (Connection con = DatabaseConnection.getConnection()) {
            boolean previousAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT id FROM alliance WHERE id = ? FOR UPDATE")) {
                    ps.setInt(1, allianceId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("Alliance does not exist: " + allianceId);
                        }
                    }
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE guilds SET allianceId = 0 WHERE guildid = ? AND allianceId = ?")) {
                    ps.setInt(1, guildId);
                    ps.setInt(2, allianceId);
                    if (ps.executeUpdate() != 1) {
                        throw new SQLException("Guild alliance state changed: " + guildId);
                    }
                }
                try (PreparedStatement ps = con.prepareStatement(
                        "DELETE FROM allianceguilds WHERE allianceid = ? AND guildid = ?")) {
                    ps.setInt(1, allianceId);
                    ps.setInt(2, guildId);
                    if (ps.executeUpdate() < 1) {
                        throw new SQLException("Alliance membership does not exist: " + guildId);
                    }
                }
                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE characters SET allianceRank = 5 " +
                                "WHERE guildid = ? AND allianceRank <> 5")) {
                    ps.setInt(1, guildId);
                    ps.executeUpdate();
                }

                con.commit();
                return true;
            } catch (Exception e) {
                rollback(con, e);
                log.error(I18nUtil.getLogMessage("Alliance.member.error2"), allianceId, guildId, e);
                return false;
            } finally {
                restoreAutoCommit(con, previousAutoCommit, "Alliance.removeGuild");
            }
        } catch (Exception e) {
            log.error(I18nUtil.getLogMessage("Alliance.member.error2"), allianceId, guildId, e);
            return false;
        }
    }

    public static boolean disbandGuild(Guild disbandingGuild, int worldId) {
        int allianceId = disbandingGuild.getAllianceId();
        int guildId = disbandingGuild.getId();
        Server srv = Server.getInstance();
        Alliance alliance = srv.getAlliance(allianceId);
        if (alliance == null) {
            return false;
        }

        synchronized (alliance) {
            boolean leaderGuild;
            try (Connection con = DatabaseConnection.getConnection()) {
                boolean previousAutoCommit = con.getAutoCommit();
                con.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = con.prepareStatement(
                            "SELECT id FROM alliance WHERE id = ? FOR UPDATE")) {
                        ps.setInt(1, allianceId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new SQLException("Alliance does not exist: " + allianceId);
                            }
                        }
                    }

                    int leaderGuildId = findLeaderGuildId(con, allianceId);
                    leaderGuild = leaderGuildId == guildId;
                    if (leaderGuild) {
                        try (PreparedStatement ps = con.prepareStatement(
                                "UPDATE characters c JOIN guilds g ON g.guildid = c.guildid " +
                                        "SET c.allianceRank = 5 WHERE g.allianceId = ? " +
                                        "AND g.guildid <> ? AND c.allianceRank <> 5")) {
                            ps.setInt(1, allianceId);
                            ps.setInt(2, guildId);
                            ps.executeUpdate();
                        }
                        try (PreparedStatement ps = con.prepareStatement(
                                "UPDATE guilds SET allianceId = 0 WHERE allianceId = ?")) {
                            ps.setInt(1, allianceId);
                            ps.executeUpdate();
                        }
                        try (PreparedStatement ps = con.prepareStatement(
                                "DELETE FROM allianceguilds WHERE allianceid = ?")) {
                            ps.setInt(1, allianceId);
                            ps.executeUpdate();
                        }
                        try (PreparedStatement ps = con.prepareStatement(
                                "DELETE FROM alliance WHERE id = ?")) {
                            ps.setInt(1, allianceId);
                            if (ps.executeUpdate() != 1) {
                                throw new SQLException("Alliance does not exist: " + allianceId);
                            }
                        }
                    } else {
                        try (PreparedStatement ps = con.prepareStatement(
                                "UPDATE guilds SET allianceId = 0 " +
                                        "WHERE guildid = ? AND allianceId = ?")) {
                            ps.setInt(1, guildId);
                            ps.setInt(2, allianceId);
                            if (ps.executeUpdate() != 1) {
                                throw new SQLException("Guild alliance state changed: " + guildId);
                            }
                        }
                        try (PreparedStatement ps = con.prepareStatement(
                                "DELETE FROM allianceguilds WHERE allianceid = ? AND guildid = ?")) {
                            ps.setInt(1, allianceId);
                            ps.setInt(2, guildId);
                            ps.executeUpdate();
                        }
                    }

                    try (PreparedStatement ps = con.prepareStatement(
                            "UPDATE characters SET guildid = 0, guildrank = 5, allianceRank = 5 " +
                                    "WHERE guildid = ?")) {
                        ps.setInt(1, guildId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = con.prepareStatement(
                            "DELETE FROM guilds WHERE guildid = ?")) {
                        ps.setInt(1, guildId);
                        if (ps.executeUpdate() != 1) {
                            throw new SQLException("Guild does not exist: " + guildId);
                        }
                    }

                    con.commit();
                } catch (Exception e) {
                    rollback(con, e);
                    log.error(I18nUtil.getLogMessage("Alliance.guildDisband.error1"),
                            allianceId, guildId, e);
                    return false;
                } finally {
                    restoreAutoCommit(con, previousAutoCommit, "Alliance.disbandGuild");
                }
            } catch (Exception e) {
                log.error(I18nUtil.getLogMessage("Alliance.guildDisband.error1"),
                        allianceId, guildId, e);
                return false;
            }

            try {
                if (leaderGuild) {
                    srv.allianceMessage(allianceId,
                            GuildPackets.disbandAlliance(allianceId), -1, -1);
                    srv.removeAllianceFromMemory(allianceId);
                } else {
                    alliance.removeGuild(guildId);
                    disbandingGuild.setAllianceIdInMemory(0);
                    disbandingGuild.resetAllianceGuildPlayersRankInMemory();
                    srv.allianceMessage(allianceId,
                            GuildPackets.removeGuildFromAlliance(alliance, guildId, worldId), -1, -1);
                    srv.allianceMessage(allianceId,
                            GuildPackets.getGuildAlliances(alliance, worldId), -1, -1);
                }
            } catch (RuntimeException e) {
                log.error(I18nUtil.getLogMessage("Alliance.runtime.error1"),
                        "Alliance.disbandGuild", allianceId, e);
            }
            return true;
        }
    }

    public static boolean disbandAlliance(int allianceId) {
        Server srv = Server.getInstance();
        Alliance alliance = srv.getAlliance(allianceId);
        if (alliance == null) {
            return false;
        }

        synchronized (alliance) {
            try (Connection con = DatabaseConnection.getConnection()) {
                boolean previousAutoCommit = con.getAutoCommit();
                con.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = con.prepareStatement(
                            "SELECT id FROM alliance WHERE id = ? FOR UPDATE")) {
                        ps.setInt(1, allianceId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new SQLException("Alliance does not exist: " + allianceId);
                            }
                        }
                    }
                    try (PreparedStatement ps = con.prepareStatement(
                            "UPDATE characters c JOIN guilds g ON g.guildid = c.guildid " +
                                    "SET c.allianceRank = 5 " +
                                    "WHERE g.allianceId = ? AND c.allianceRank <> 5")) {
                        ps.setInt(1, allianceId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = con.prepareStatement(
                            "UPDATE guilds SET allianceId = 0 WHERE allianceId = ?")) {
                        ps.setInt(1, allianceId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = con.prepareStatement(
                            "DELETE FROM allianceguilds WHERE allianceid = ?")) {
                        ps.setInt(1, allianceId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = con.prepareStatement(
                            "DELETE FROM alliance WHERE id = ?")) {
                        ps.setInt(1, allianceId);
                        if (ps.executeUpdate() != 1) {
                            throw new SQLException("Alliance does not exist: " + allianceId);
                        }
                    }
                    con.commit();
                } catch (Exception e) {
                    rollback(con, e);
                    log.error(I18nUtil.getLogMessage("Alliance.disband.error1"), allianceId, e);
                    return false;
                } finally {
                    restoreAutoCommit(con, previousAutoCommit, "Alliance.disband");
                }
            } catch (Exception e) {
                log.error(I18nUtil.getLogMessage("Alliance.disband.error1"), allianceId, e);
                return false;
            }

            try {
                srv.allianceMessage(allianceId, GuildPackets.disbandAlliance(allianceId), -1, -1);
            } catch (RuntimeException e) {
                log.error(I18nUtil.getLogMessage("Alliance.runtime.error1"),
                        "Alliance.disband.broadcast", allianceId, e);
            }
            try {
                srv.removeAllianceFromMemory(allianceId);
            } catch (RuntimeException e) {
                log.error(I18nUtil.getLogMessage("Alliance.runtime.error1"),
                        "Alliance.disband.cache", allianceId, e);
            }
            return true;
        }
    }

    public static GuildRemovalResult removeGuildFromAlliance(int allianceId, int guildId, int worldId) {
        Server srv = Server.getInstance();
        Alliance alliance = srv.getAlliance(allianceId);
        if (alliance == null) {
            return GuildRemovalResult.FAILED;
        }

        synchronized (alliance) {
            int leaderGuildId = alliance.getLeaderGuildId();
            if (leaderGuildId < 1) {
                return GuildRemovalResult.FAILED;
            }
            if (leaderGuildId == guildId) {
                return GuildRemovalResult.LEADER_GUILD;
            }

            if (!srv.removeGuildFromAlliance(alliance.getId(), guildId)) {
                return GuildRemovalResult.FAILED;
            }
            try {
                srv.allianceMessage(alliance.getId(),
                        GuildPackets.removeGuildFromAlliance(alliance, guildId, worldId), -1, -1);
                srv.allianceMessage(alliance.getId(),
                        GuildPackets.getGuildAlliances(alliance, worldId), -1, -1);
                srv.allianceMessage(alliance.getId(),
                        GuildPackets.allianceNotice(alliance.getId(), alliance.getNotice()), -1, -1);
                srv.guildMessage(guildId, GuildPackets.disbandAlliance(alliance.getId()));

                Guild guild = srv.getGuild(guildId, worldId);
                if (guild != null) {
                    alliance.dropMessage("[" + guild.getName() + "] guild has left the union.");
                }
            } catch (RuntimeException e) {
                log.error(I18nUtil.getLogMessage("Alliance.runtime.error1"),
                        "Alliance.removeGuild", allianceId, e);
            }
            return GuildRemovalResult.SUCCESS;
        }
    }

    public void updateAlliancePackets(Character chr) {
        if (allianceId > 0) {
            this.broadcastMessage(GuildPackets.updateAllianceInfo(this, chr.getWorld()));
            this.broadcastMessage(GuildPackets.allianceNotice(this.getId(), this.getNotice()));
        }
    }

    public boolean removeGuild(int gid) {
        synchronized (guilds) {
            int index = getGuildIndex(gid);
            if (index == -1) {
                return false;
            }

            guilds.remove(index);
            return true;
        }
    }

    public boolean addGuild(int gid) {
        synchronized (guilds) {
            if (guilds.size() >= capacity || getGuildIndex(gid) > -1) {
                return false;
            }

            guilds.add(gid);
            return true;
        }
    }

    private int getGuildIndex(int gid) {
        synchronized (guilds) {
            for (int i = 0; i < guilds.size(); i++) {
                if (guilds.get(i) == gid) {
                    return i;
                }
            }
            return -1;
        }
    }

    public String getRankTitle(int rank) {
        return rankTitles[rank - 1];
    }

    public List<Integer> getGuilds() {
        synchronized (guilds) {
            List<Integer> guilds_ = new LinkedList<>();
            for (int guild : guilds) {
                if (guild != -1) {
                    guilds_.add(guild);
                }
            }
            return guilds_;
        }
    }

    public String getAllianceNotice() {
        return notice;
    }

    public String getNotice() {
        return notice;
    }

    public synchronized boolean purchaseCapacity(Character buyer, int cost, int maxCapacity) {
        Guild buyerGuild = Server.getInstance().getGuild(buyer.getGuildId());
        if (cost <= 0 || maxCapacity <= 0 || buyerGuild == null ||
                buyerGuild.getAllianceId() != allianceId ||
                buyer.getAllianceRank() != 1 || capacity >= maxCapacity) {
            return false;
        }
        synchronized (guilds) {
            if (getGuildIndex(buyer.getGuildId()) == -1) {
                return false;
            }
        }

        int expectedCapacity = capacity;
        boolean purchased = buyer.spendMesoTransactionally(cost,
                balanceAfter -> saveCapacityPurchase(
                        buyer.getId(), balanceAfter, expectedCapacity,
                        expectedCapacity + 1, maxCapacity));
        if (!purchased) {
            return false;
        }
        capacity = expectedCapacity + 1;
        return true;
    }

    private boolean saveCapacityPurchase(int buyerId, int balanceAfter,
                                         int expectedCapacity, int newCapacity,
                                         int maxCapacity) {
        try (Connection con = DatabaseConnection.getConnection()) {
            boolean previousAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE alliance SET capacity = ? " +
                                "WHERE id = ? AND capacity = ? AND capacity < ?")) {
                    ps.setInt(1, newCapacity);
                    ps.setInt(2, allianceId);
                    ps.setInt(3, expectedCapacity);
                    ps.setInt(4, maxCapacity);
                    if (ps.executeUpdate() != 1) {
                        throw new SQLException("Alliance capacity state changed: " + allianceId);
                    }
                }
                updateCharacterMeso(con, buyerId, balanceAfter);
                con.commit();
                return true;
            } catch (Exception e) {
                rollback(con, e);
                log.error(I18nUtil.getLogMessage("Alliance.capacity.error1"),
                        allianceId, buyerId, e);
                return false;
            } finally {
                restoreAutoCommit(con, previousAutoCommit, "Alliance.purchaseCapacity");
            }
        } catch (Exception e) {
            log.error(I18nUtil.getLogMessage("Alliance.capacity.error1"),
                    allianceId, buyerId, e);
            return false;
        }
    }

    private boolean addLoadedGuild(int guildId) {
        synchronized (guilds) {
            if (getGuildIndex(guildId) > -1) {
                return false;
            }
            guilds.add(guildId);
            return true;
        }
    }

    private synchronized void setCapacity(int newCapacity) {
        this.capacity = newCapacity;
    }

    public synchronized int getCapacity() {
        return this.capacity;
    }

    private static void updateCharacterMeso(Connection con, int characterId,
                                            int balanceAfter) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE characters SET meso = ? WHERE id = ?")) {
            ps.setInt(1, balanceAfter);
            ps.setInt(2, characterId);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Character does not exist: " + characterId);
            }
        }
    }

    private static void rollback(Connection con, Exception cause) {
        try {
            con.rollback();
        } catch (SQLException rollbackError) {
            cause.addSuppressed(rollbackError);
        }
    }

    private static void restoreAutoCommit(Connection con, boolean autoCommit, String operation) {
        try {
            con.setAutoCommit(autoCommit);
        } catch (SQLException e) {
            log.error(I18nUtil.getLogMessage("Alliance.connection.error1"), operation, e);
        }
    }

    public int getId() {
        return allianceId;
    }

    public String getName() {
        return name;
    }

    public GuildCharacter getLeader() {
        synchronized (guilds) {
            for (Integer gId : guilds) {
                Guild guild = Server.getInstance().getGuild(gId);
                if (guild == null) {
                    continue;
                }
                GuildCharacter mgc = guild.getMGC(guild.getLeaderId());

                if (mgc != null && mgc.getAllianceRank() == 1) {
                    return mgc;
                }
            }

            return null;
        }
    }

    private int getLeaderGuildId() {
        GuildCharacter leader = getLeader();
        if (leader != null) {
            return leader.getGuildId();
        }

        try (Connection con = DatabaseConnection.getConnection()) {
            return findLeaderGuildId(con, allianceId);
        } catch (Exception e) {
            log.error(I18nUtil.getLogMessage("Alliance.load.error2"), allianceId, e);
            return -1;
        }
    }

    public synchronized boolean canRemoveGuild(int guildId) {
        int leaderGuildId = getLeaderGuildId();
        return leaderGuildId > 0 && leaderGuildId != guildId;
    }

    private static int findLeaderGuildId(Connection con, int allianceId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT DISTINCT g.guildid FROM characters c " +
                        "JOIN guilds g ON g.guildid = c.guildid " +
                        "JOIN allianceguilds ag ON ag.guildid = g.guildid " +
                        "AND ag.allianceid = g.allianceId " +
                        "WHERE g.allianceId = ? AND c.id = g.leader " +
                        "AND c.allianceRank = 1")) {
            ps.setInt(1, allianceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Alliance leader does not exist: " + allianceId);
                }
                int leaderGuildId = rs.getInt("guildid");
                if (rs.next()) {
                    throw new SQLException("Alliance has multiple leaders: " + allianceId);
                }
                return leaderGuildId;
            }
        }
    }

    public void dropMessage(String message) {
        dropMessage(5, message);
    }

    public void dropMessage(int type, String message) {
        synchronized (guilds) {
            for (Integer gId : guilds) {
                Guild guild = Server.getInstance().getGuild(gId);
                if (guild != null) {
                    guild.dropMessage(type, message);
                }
            }
        }
    }

    public void broadcastMessage(Packet packet) {
        Server.getInstance().allianceMessage(allianceId, packet, -1, -1);
    }

    public static void sendInvitation(Client c, String targetGuildName, int allianceId) {
        Guild mg = Server.getInstance().getGuildByName(targetGuildName);
        if (mg == null) {
            c.getPlayer().dropMessage(5, "The entered guild does not exist.");
        } else {
            if (mg.getAllianceId() > 0) {
                c.getPlayer().dropMessage(5, "The entered guild is already registered on a guild alliance.");
            } else {
                Character victim = mg.getMGC(mg.getLeaderId()).getCharacter();
                if (victim == null) {
                    c.getPlayer().dropMessage(5, "The master of the guild that you offered an invitation is currently not online.");
                } else {
                    if (InviteCoordinator.createInvite(InviteType.ALLIANCE, c.getPlayer(), allianceId, victim.getId())) {
                        victim.sendPacket(GuildPackets.allianceInvite(allianceId, c.getPlayer()));
                    } else {
                        c.getPlayer().dropMessage(5, "The master of the guild that you offered an invitation is currently managing another invite.");
                    }
                }
            }
        }
    }

    public static boolean answerInvitation(int targetId, String targetGuildName, int allianceId, boolean answer) {
        InviteResult res = InviteCoordinator.answerInvite(InviteType.ALLIANCE, targetId, allianceId, answer);

        String msg;
        Character sender = res.from;
        switch (res.result) {
            case ACCEPTED:
                return true;

            case DENIED:
                msg = "[" + targetGuildName + "] guild has denied your guild alliance invitation.";
                break;

            default:
                msg = "The guild alliance request has not been accepted, since the invitation expired.";
        }

        if (sender != null) {
            sender.dropMessage(5, msg);
        }

        return false;
    }
}
