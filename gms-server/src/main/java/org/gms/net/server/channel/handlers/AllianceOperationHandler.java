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
package org.gms.net.server.channel.handlers;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.net.server.Server;
import org.gms.net.server.guild.Alliance;
import org.gms.net.server.guild.Guild;
import org.gms.net.server.guild.GuildCharacter;
import org.gms.net.server.guild.GuildPackets;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;

/**
 * @author XoticStory, Ronan
 */
public final class AllianceOperationHandler extends AbstractPacketHandler {

    @Override
    public final void handlePacket(InPacket p, Client c) {
        Alliance alliance = null;
        Character chr = c.getPlayer();

        if (chr.getGuild() == null) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        if (chr.getGuild().getAllianceId() > 0) {
            alliance = chr.getAlliance();
        }

        byte b = p.readByte();
        if (alliance == null) {
            if (b != 4) {
                c.sendPacket(PacketCreator.enableActions());
                return;
            }
        } else {
            if (b == 4) {
                chr.dropMessage(5, I18nUtil.getMessage("AllianceOperationHandler.message4"));
                c.sendPacket(PacketCreator.enableActions());
                return;
            }

            if (chr.getMGC().getAllianceRank() > 2 || !alliance.getGuilds().contains(chr.getGuildId())) {
                c.sendPacket(PacketCreator.enableActions());
                return;
            }
        }

        // "alliance" is only null at case 0x04
        switch (b) {
            case 0x01:
                Server.getInstance().allianceMessage(alliance.getId(), GuildPackets.sendShowInfo(chr.getGuild().getAllianceId(), chr.getId()), -1, -1);
                break;
            case 0x02: { // Leave Alliance
                if (chr.getGuild().getAllianceId() == 0 || chr.getGuildId() < 1 || chr.getGuildRank() != 1) {
                    return;
                }

                Alliance.GuildRemovalResult removalResult = Alliance.removeGuildFromAlliance(
                        chr.getGuild().getAllianceId(), chr.getGuildId(), chr.getWorld());
                if (removalResult != Alliance.GuildRemovalResult.SUCCESS) {
                    chr.dropMessage(5, I18nUtil.getMessage("AllianceOperationHandler.message2"));
                }
                break;
            }
            case 0x03: // Send Invite
                String guildName = p.readString();

                if (alliance.getGuilds().size() >= alliance.getCapacity()) {
                    chr.dropMessage(5, I18nUtil.getMessage("AllianceOperationHandler.message5"));
                } else {
                    Alliance.sendInvitation(c, guildName, alliance.getId());
                }

                break;
            case 0x04: { // Accept Invite
                Guild guild = chr.getGuild();
                if (guild.getAllianceId() != 0 || chr.getGuildRank() != 1 || chr.getGuildId() < 1) {
                    return;
                }

                int allianceid = p.readInt();
                //slea.readMapleAsciiString();  //recruiter's guild name

                alliance = Server.getInstance().getAlliance(allianceid);
                if (alliance == null) {
                    return;
                }

                if (!Alliance.answerInvitation(c.getPlayer().getId(), guild.getName(), alliance.getId(), true)) {
                    return;
                }

                if (alliance.getGuilds().size() >= alliance.getCapacity()) {
                    chr.dropMessage(5, I18nUtil.getMessage("AllianceOperationHandler.message5"));
                    return;
                }

                int guildid = chr.getGuildId();

                if (!Server.getInstance().addGuildToAlliance(alliance.getId(), guildid, chr.getId())) {
                    chr.dropMessage(5, I18nUtil.getMessage("AllianceOperationHandler.message1"));
                    return;
                }

                chr.getMGC().setAllianceRank(2);
                Guild g = Server.getInstance().getGuild(chr.getGuildId());
                if (g != null) {
                    GuildCharacter guildMaster = g.getMGC(chr.getId());
                    if (guildMaster != null) {
                        guildMaster.setAllianceRank(2);
                    }
                }

                Server.getInstance().allianceMessage(alliance.getId(), GuildPackets.addGuildToAlliance(alliance, guildid, c), -1, -1);
                Server.getInstance().allianceMessage(alliance.getId(), GuildPackets.updateAllianceInfo(alliance, c.getWorld()), -1, -1);
                Server.getInstance().allianceMessage(alliance.getId(), GuildPackets.allianceNotice(alliance.getId(), alliance.getNotice()), -1, -1);
                guild.dropMessage(I18nUtil.getMessage(
                        "AllianceOperationHandler.message6", alliance.getName()));

                break;
            }
            case 0x06: { // Expel Guild
                int guildid = p.readInt();
                int allianceid = p.readInt();
                if (chr.getAllianceRank() != 1 || chr.getGuild().getAllianceId() == 0 ||
                        chr.getGuild().getAllianceId() != allianceid) {
                    return;
                }

                Guild expelledGuild = Server.getInstance().getGuild(guildid);
                if (expelledGuild == null) {
                    chr.dropMessage(5, I18nUtil.getMessage("AllianceOperationHandler.message2"));
                    return;
                }
                String expelledGuildName = expelledGuild.getName();

                if (!Server.getInstance().removeGuildFromAlliance(alliance.getId(), guildid)) {
                    chr.dropMessage(5, I18nUtil.getMessage("AllianceOperationHandler.message2"));
                    return;
                }
                Server.getInstance().allianceMessage(alliance.getId(), GuildPackets.removeGuildFromAlliance(alliance, guildid, c.getWorld()), -1, -1);

                Server.getInstance().allianceMessage(alliance.getId(), GuildPackets.getGuildAlliances(alliance, c.getWorld()), -1, -1);
                Server.getInstance().allianceMessage(alliance.getId(), GuildPackets.allianceNotice(alliance.getId(), alliance.getNotice()), -1, -1);
                Server.getInstance().guildMessage(guildid, GuildPackets.disbandAlliance(allianceid));

                alliance.dropMessage(I18nUtil.getMessage(
                        "AllianceOperationHandler.message7", expelledGuildName));
                break;
            }
            case 0x07: { // Change Alliance Leader
                if (chr.getAllianceRank() != 1 || chr.getGuild().getAllianceId() == 0 ||
                        chr.getGuildId() < 1) {
                    return;
                }
                int victimid = p.readInt();
                Character player = Server.getInstance().getWorld(c.getWorld()).getPlayerStorage().getCharacterById(victimid);
                if (player == null || player.getAllianceRank() != 2) {
                    return;
                }

                if (!alliance.changeLeader(chr, player)) {
                    chr.dropMessage(5, I18nUtil.getMessage("AllianceOperationHandler.message3"));
                    return;
                }
                Server.getInstance().allianceMessage(alliance.getId(),
                        GuildPackets.getGuildAlliances(alliance, player.getWorld()), -1, -1);
                alliance.dropMessage(I18nUtil.getMessage(
                        "AllianceOperationHandler.message8", player.getName()));
                break;
            }
            case 0x08:
                String[] ranks = new String[5];
                for (int i = 0; i < 5; i++) {
                    ranks[i] = p.readString();
                }
                if (!alliance.updateRankTitles(ranks)) {
                    return;
                }
                Server.getInstance().allianceMessage(alliance.getId(), GuildPackets.changeAllianceRankTitle(alliance.getId(), ranks), -1, -1);
                break;
            case 0x09: {
                int int1 = p.readInt();
                byte byte1 = p.readByte();

                //Server.getInstance().allianceMessage(alliance.getId(), sendChangeRank(chr.getGuild().getAllianceId(), chr.getId(), int1, byte1), -1, -1);
                Character player = Server.getInstance().getWorld(c.getWorld()).getPlayerStorage().getCharacterById(int1);
                changePlayerAllianceRank(alliance, player, (byte1 > 0));

                break;
            }
            case 0x0A:
                String notice = p.readString();
                if (!alliance.updateNotice(notice)) {
                    return;
                }
                Server.getInstance().allianceMessage(alliance.getId(), GuildPackets.allianceNotice(alliance.getId(), notice), -1, -1);

                alliance.dropMessage(5, I18nUtil.getMessage(
                        "AllianceOperationHandler.message9", notice));
                break;
            default:
                chr.dropMessage(I18nUtil.getMessage("AllianceOperationHandler.message10"));
        }

    }

    private void changePlayerAllianceRank(Alliance alliance, Character chr, boolean raise) {
        int newRank = chr.getAllianceRank() + (raise ? -1 : 1);
        if (newRank < 3 || newRank > 5) {
            return;
        }

        chr.getMGC().setAllianceRank(newRank);
        chr.saveGuildStatus();

        Server.getInstance().allianceMessage(alliance.getId(), GuildPackets.getGuildAlliances(alliance, chr.getWorld()), -1, -1);
        alliance.dropMessage(I18nUtil.getMessage(
                "AllianceOperationHandler.message11", chr.getName(),
                alliance.getRankTitle(newRank)));
    }

}
