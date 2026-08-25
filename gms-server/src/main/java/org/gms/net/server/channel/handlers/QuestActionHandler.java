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
import org.gms.constants.id.MapId;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.scripting.quest.QuestScriptManager;
import org.gms.server.life.NPC;
import org.gms.server.quest.Quest;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;

import java.awt.*;

/**
 * @author Matze
 */
public final class QuestActionHandler extends AbstractPacketHandler {
    private static final short LOST_WHITE_ESSENCE_QUEST = 4522;
    private static final short CAPTAIN_LATANICA_RETURN_QUEST = 4523;
    private static final int WHITE_ESSENCE = 4000381;

    private static void sendNpcOk(Client c, int npc, String message) {
        c.sendPacket(PacketCreator.getNPCTalk(npc, (byte) 0, message, "00 00", (byte) 0));
    }

    // isNpcNearby thanks to GabrielSin
    private static boolean isNpcNearby(InPacket p, Character player, Quest quest, int npcId)
    {
        Point playerP;

        // 获取玩家当前坐标
        Point pos = player.getPosition();

        if (p.available() >= 4)
        {
            // InPacket 余下能解析的字段大于4
            // 构建玩家坐标
            playerP = new Point(p.readShort(), p.readShort());
            if (playerP.distance(pos) > 1000)
            {
                // 如果协议坐标大于玩家坐标 1000
                // 则玩家坐标由 playerP 存储

                // thanks Darter (YungMoozi) for reporting unchecked player position
                playerP = pos;
            }
        }
        else
        {
            playerP = pos;
        }

        if (!quest.isAutoStart()
                && !quest.isAutoComplete())
        {
            // NPCID的检测

            NPC npc = player.getMap().getNPCById(npcId);
            if (npc == null)
            {
                return false;
            }

            // 检测NPC是否在玩家附近
            Point npcP = npc.getPosition();
            if (Math.abs(npcP.getX() - playerP.getX()) > 1200
                    || Math.abs(npcP.getY() - playerP.getY()) > 800)
            {
                player.dropMessage(5, I18nUtil.getMessage("QuestActionHandler.isNpcNearby.message1"));
                return false;
            }
        }

        return true;
    }

    @Override
    public final void handlePacket(InPacket p, Client c)
    {
        byte action = p.readByte();

        short questid = p.readShort();
        // 通过任务ID，构建 Quest 对象
        Quest quest = Quest.getInstance(questid);

        Character player = c.getPlayer();

        if (player.getMapId() == MapId.JAIL)
        {   //监狱地图不可使用任务脚本
            player.dropMessage(1,I18nUtil.getMessage("ActionHandler.map.message1"));
            return;
        }

        switch (action)
        {
            case 0:
                // 找回丢失物品，鸣谢 Darter (Rajan)
                // Restore lost item, Credits Darter ( Rajan )
                p.readInt();
                int itemid = p.readInt();
                quest.restoreLostItem(player, itemid);
                break;
            case 1:
            {
                // Start Quest
                int npc = p.readInt();
                if (!isNpcNearby(p, player, quest, npc))
                {
                    return;
                }


                if (quest.canStart(player, npc))
                {
                    // 任务无法直接开始

                    boolean success = QuestScriptManager.getInstance().checkFunctionExists(
                            c
                            , questid
                            , npc
                            , "start");

                    boolean hasScriptRequirement = quest.hasScriptRequirement(false);

                    if (hasScriptRequirement && success)
                    {
                        // 任务具有脚本要求
                        QuestScriptManager.getInstance().start(c, questid, npc);
                    }
                    else
                    {
                        // 纯WZ任务，无脚本要求
                        quest.start(player, npc);
                    }
                }
                else if (questid == LOST_WHITE_ESSENCE_QUEST
                        && player.haveItem(WHITE_ESSENCE))
                {
                    // 遗失的白色精华 任务?
                    sendNpcOk(c, npc, I18nUtil.getMessage("QuestActionHandler.hasWhiteEssence.message1"));
                }
                else if (questid == CAPTAIN_LATANICA_RETURN_QUEST
                        && player.haveItem(WHITE_ESSENCE))
                {
                    // 拉塔尼卡船长归来 任务?
                    sendNpcOk(c, npc, I18nUtil.getMessage("QuestActionHandler.hasWhiteEssenceForLatanica.message1"));
                }

                break;
            }
            case 2:
            {
                // Complete Quest
                int npc = p.readInt();
                if (!isNpcNearby(p, player, quest, npc))
                {
                    return;
                }

                if (quest.canComplete(player, npc))
                {
                    boolean success = QuestScriptManager.getInstance().checkFunctionExists(c, questid, npc, "end");
                    boolean hasScriptRequirement = quest.hasScriptRequirement(true);
                    if (hasScriptRequirement && success)
                    {
                        QuestScriptManager.getInstance().end(c, questid, npc);
                    }
                    else
                    {
                        if (p.available() >= 2)
                        {
                            int selection = p.readShort();
                            quest.complete(player, npc, selection);
                        }
                        else
                        {
                            quest.complete(player, npc);
                        }
                    }
                }
                break;
            }
            case 3:
                // forfeit quest
                quest.forfeit(player);
                break;
            case 4:
            {
                // scripted start quest
                int npc = p.readInt();
                if (!isNpcNearby(p, player, quest, npc))
                {
                    return;
                }

                if (quest.canStart(player, npc))
                {
                    QuestScriptManager.getInstance().start(c, questid, npc);
                }
                break;
            }
            case 5:
            {
                // scripted end quests
                int npc = p.readInt();
                if (!isNpcNearby(p, player, quest, npc))
                {
                    return;
                }

                if (quest.canComplete(player, npc))
                {
                    QuestScriptManager.getInstance().end(c, questid, npc);
                }
                break;
            }
        }
    }
}
