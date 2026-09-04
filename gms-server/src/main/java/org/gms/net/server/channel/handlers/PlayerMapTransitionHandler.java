/*
    This file is part of the HeavenMS MapleStory Server
    Copyleft (L) 2016 - 2019 RonanLana

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

import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapObject;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;

import java.util.Collections;
import java.util.List;

/**
 * @author Ronan
 * 玩家完成切换地图触发
 */
public final class PlayerMapTransitionHandler extends AbstractPacketHandler {

    @Override
    public final void handlePacket(InPacket p, Client c) {

        Character chr = c.getPlayer();
        chr.setMapTransitionComplete();

        // 追踪类型buffer
        int beaconid = chr.getBuffSource(BuffStat.HOMING_BEACON);
        if (beaconid != -1)
        {
            // 清理掉当前角色buffer
            chr.cancelBuffStats(BuffStat.HOMING_BEACON);

            final List<Pair<BuffStat, Integer>> stat = Collections.singletonList(new Pair<>(BuffStat.HOMING_BEACON, 0));
            chr.sendPacket(PacketCreator.giveBuff(1, beaconid, stat));
        }

        if (!chr.isHidden())
        {
            // thanks Lame (Conrad) for noticing hidden characters controlling mobs
            for (MapObject mo : chr.getMap().getMonsters())
            {
                // thanks BHB, IxianMace, Jefe for noticing several issues regarding mob statuses (such as freeze)
                Monster m = (Monster) mo;
                if (m.getSpawnEffect() == 0 || m.getHp() < m.getMaxHp())
                {
                    // avoid effect-spawning mobs
                    if (m.getController() == chr)
                    {
                        /**
                         *  如果怪物的控制权为当前进入的玩家
                         *  防止是当前玩家“掉线后重连”，可能怪物控制权在客户端已经没有了
                         *  所以服务端需要先把怪物控制权给回收，在重新赋予给当前进入地图的玩家
                         *  sendDestroyData 是为了让客户端把当前怪物节点给清理掉
                         * **/

                        c.sendPacket(PacketCreator.stopControllingMonster(m.getObjectId()));

                        m.sendDestroyData(c);

                        // 清理当前怪物控制权
                        m.aggroRemoveController();
                    }
                    else
                    {
                        // 如果怪物所属权不为当前角色
                        // 那么直接通知客户端把当前怪物节点移除
                        m.sendDestroyData(c);
                    }

                    /**
                     *  重新把当前怪物信息发送给客户端
                     * **/
                    m.sendSpawnData(c);

                    // 当前怪物控制权修改为当前进入地图的角色。
                    m.aggroSwitchController(chr, false);
                }
            }
        }
    }
}