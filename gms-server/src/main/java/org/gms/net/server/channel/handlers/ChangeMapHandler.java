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
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.constants.id.ItemId;
import org.gms.constants.id.MapId;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.net.server.Server;
import org.gms.util.I18nUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.server.Trade;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.util.PacketCreator;

import java.awt.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.StringJoiner;

/**
 * 玩家通过光圈切换地图触发
 */
public final class ChangeMapHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChangeMapHandler.class);

    @Override
    public void handlePacket(InPacket p, Client c)
    {
        Character chr = c.getPlayer();
        if (chr.isChangingMaps() || chr.isBanned())
        {
            if (chr.isChangingMaps())
            {
                log.warn(I18nUtil.getLogMessage("ChangeMapHandler.warn.message1"),
                        chr.getName(),      //玩家角色名称
                        chr.getMap().getMapName(),  //当前地图名称
                        chr.getMapId(),             //当前地图ID
                        getFormattedMapListLogMessage(chr.getLastVisitedMapIds(),c)  //最近访问的地图列表
                );
            }

            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        if (chr.getTrade() != null)
        {
            Trade.cancelTrade(chr, Trade.TradeResult.UNSUCCESSFUL_ANOTHER_MAP);
        }

        boolean enteringMapFromCashShop = p.available() == 0;
        if (enteringMapFromCashShop)
        {
            // 进入商城
            enterFromCashShop(c);
            return;
        }

        if (chr.getCashShop().isOpened())
        {
            // 打开了现金商店页面
            // 应该是切换地图产生了异常，直接断开
            c.disconnect(false, false);
            return;
        }

        try
        {
            p.readByte(); // 1 = from dying 0 = regular portals

            // 目标地图ID
            int targetMapId = p.readInt();
            // 传送门名称
            String portalName = p.readString();

            // 通过名称拿到 传送门
            Portal portal = chr.getMap().getPortal(portalName);

            // 跳过字节
            p.readByte();

            boolean wheel = p.readByte() > 0;

            boolean chasing = p.readByte() == 1
                    && chr.isGM()
                    && p.available() == 2 * Integer.BYTES;
            if (chasing)
            {
                chr.setChasing(true);
                chr.setPosition(new Point(p.readInt(), p.readInt()));
            }

            if (targetMapId != -1)
            {
                if (!chr.isAlive())
                {
                    // 玩家死亡切换地图
                    MapleMap map = chr.getMap();
                    if (wheel
                            && chr.haveItemWithId(ItemId.WHEEL_OF_FORTUNE, false))
                    {
                        // 玩家有命运之轮道具 → 扣除一个轮子 → 原地复活
                        // thanks lucasziron (lziron) for showing revivePlayer() triggering by Wheel

                        InventoryManipulator.removeById(c, InventoryType.CASH, ItemId.WHEEL_OF_FORTUNE, 1, true, false);

                        chr.sendPacket(
                                PacketCreator.showWheelsLeft(
                                        chr.getItemQuantity(ItemId.WHEEL_OF_FORTUNE, false)));

                        chr.updateHp(50);
                        chr.changeMap(
                                map
                                , map.findClosestPlayerSpawnpoint(chr.getPosition()));
                    }
                    else
                    {
                        boolean executeStandardPath = true;
                        if (chr.getEventInstance() != null)
                        {
                            executeStandardPath = chr.getEventInstance().revivePlayer(chr);
                        }

                        if (executeStandardPath)
                        {
                            chr.respawn(map.getReturnMapId());
                        }
                    }
                }
                else
                {
                    // 玩家还活着切换地图
                    if (chr.isGM())
                    {
                        // GM 随意传送；普通玩家只允许在固定的几个新手/剧情地图之间按白名单传送，其余换图请求被忽略
                        MapleMap to = chr.getWarpMap(targetMapId);
                        chr.changeMap(to, to.getPortal(0));
                    }
                    else
                    {
                        // 限制普通玩家只能走"剧情/新手流程允许"的特殊换图，防止绕过新手教程或通过伪造封包乱传。
                        final int divi = chr.getMapId() / 100;
                        boolean warp = false;
                        if (divi == 0)
                        {
                            // 新手村 → 训练场
                            if (targetMapId == 10000)
                            {
                                warp = true;
                            }
                        }
                        else if (divi == 20100)
                        {
                            // 骑士团剧情 到 里恩港
                            if (targetMapId == MapId.LITH_HARBOUR)
                            {
                                c.sendPacket(PacketCreator.lockUI(false));
                                c.sendPacket(PacketCreator.disableUI(false));
                                warp = true;
                            }
                        }
                        else if (divi == 9130401)
                        {
                            // 皇家骑士团新手地图
                            // Only allow warp if player is already in Intro map, or else = hack
                            if (targetMapId == MapId.EREVE || targetMapId / 100 == 9130401)
                            { // Cygnus introduction
                                warp = true;
                            }
                        }
                        else if (divi == 9140900)
                        {
                            // 阿兰新手地图
                            // Aran Introduction
                            if (targetMapId == MapId.ARAN_TUTO_2
                                    || targetMapId == MapId.ARAN_TUTO_3
                                    || targetMapId == MapId.ARAN_TUTO_4
                                    || targetMapId == MapId.ARAN_INTRO)
                            {
                                warp = true;
                            }
                        }
                        else if (divi / 10 == 1020)
                        {
                            // 冒险家新手电影
                            // Adventurer movie clip Intro
                            if (targetMapId == 1020000)
                            {
                                warp = true;
                            }
                        }
                        else if (divi / 10 >= 980040 && divi / 10 <= 980045)
                        {
                            // 巫女塔入口
                            if (targetMapId == MapId.WITCH_TOWER_ENTRANCE)
                            {
                                warp = true;
                            }
                        }

                        if (warp)
                        {
                            final MapleMap to = chr.getWarpMap(targetMapId);
                            chr.changeMap(to, to.getPortal(0));
                        }
                    }
                }
            }

            if (portal != null && !portal.getPortalStatus())
            {
                // 传送门 被关闭
                c.sendPacket(PacketCreator.blockedMessage(1));
                c.sendPacket(PacketCreator.enableActions());
                return;
            }

            if (chr.getMapId() == MapId.FITNESS_EVENT_LAST)
            {
                // 在 忍耐任务（FITNESS）终点图  活动计时器重置
                chr.getFitness().resetTimes();
            }
            else if (chr.getMapId() == MapId.OLA_EVENT_LAST_1
                    || chr.getMapId() == MapId.OLA_EVENT_LAST_2)
            {
                chr.getOla().resetTimes();
            }

            if (portal != null)
            {
                if (portal.getPosition().distanceSq(chr.getPosition()) > 400000)
                {
                    // 检查玩家距离传送门距离超过  400000 判定为穿门/伪造换图 拒绝
                    c.sendPacket(PacketCreator.enableActions());
                    return;
                }

                // 由传送门对象自己执行换图
                portal.enterPortal(c);
            }
            else
            {
                // 传送门对象为空
                c.sendPacket(PacketCreator.enableActions());
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private void enterFromCashShop(Client c) {
        final Character chr = c.getPlayer();

        if (!chr.getCashShop().isOpened()) {
            c.disconnect(false, false);
            return;
        }
        String[] socket = Server.getInstance().getInetSocket(c, c.getWorld(), c.getChannel());
        if (socket == null) {
            c.enableCSActions();
            return;
        }
        chr.getCashShop().open(false);

        chr.setSessionTransitionState();
        try {
            c.sendPacket(PacketCreator.getChannelChange(InetAddress.getByName(socket[0]), Integer.parseInt(socket[1])));
        } catch (UnknownHostException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * 提供地图ID列表 返回格式化地图名称+地图ID
     * @param MapIds 传入地图ID列表
     * @param c 传入客户端
     * @return [蘑菇村西入口 (0),自由市场 (910000000)]
     */
    private static String getFormattedMapListLogMessage(List<Integer> MapIds,Client c) {
        StringJoiner sj = new StringJoiner(", ", "[", "]");
        for (int mapid : MapIds) {
            MapleMap map = null;
            try {
                map = c.getChannelServer().getMapFactory().getMap(mapid);
            } catch (Exception ignored) {}
            String MapName = I18nUtil.getLogMessage("SystemRescue.info.map.message1");  //未知地图
            MapName = map != null && !map.getMapName().isEmpty() ? map.getMapName() : MapName;
            sj.add(String.format("%s (%d)", MapName, mapid));
        }
        return sj.toString();
    }
}