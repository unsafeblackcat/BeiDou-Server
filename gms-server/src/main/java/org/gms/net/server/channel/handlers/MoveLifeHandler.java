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
import org.gms.config.GameConfig;
import org.gms.net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.server.life.MobSkill;
import org.gms.server.life.MobSkillFactory;
import org.gms.server.life.MobSkillId;
import org.gms.server.life.MobSkillType;
import org.gms.server.life.Monster;
import org.gms.server.life.MonsterInformationProvider;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;
import org.gms.exception.EmptyMovementException;

import java.awt.*;
import java.util.LinkedList;
import java.util.List;

/**
 * @author Danny (Leifde)
 * @author ExtremeDevilz
 * @author Ronan (HeavenMS)
 */
public final class MoveLifeHandler extends AbstractMovementPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(MoveLifeHandler.class);

    @Override
    public void handlePacket(InPacket p, Client c)
    {
        Character player = c.getPlayer();
        MapleMap map = player.getMap();

        if (player.isChangingMaps())
        {
            // thanks Lame for noticing mob movement shuffle (mob OID on different maps) happening on map transitions
            return;
        }

        int objectid = p.readInt();
        short moveid = p.readShort();
        MapObject mmo = map.getMapObject(objectid);
        if (mmo == null || mmo.getType() != MapObjectType.MONSTER)
        {
            return;
        }

        Monster monster = (Monster) mmo;
        List<Character> banishPlayers = null;

        byte pNibbles = p.readByte();

        // 客户端发过来的怪物动作
        byte rawActivity = p.readByte();

        int skillId = p.readByte() & 0xff;
        int skillLv = p.readByte() & 0xff;

        // 补充动作的细节
        short pOption = p.readShort();

        p.skip(8);

        if (rawActivity >= 0)
        {
            rawActivity = (byte) (rawActivity & 0xFF >> 1);
        }

        // 判断动作是否，怪物普通攻击
        // 判断动作是否，怪物释放了技能
        boolean isAttack = inRangeInclusive(rawActivity, 24, 41);
        boolean isSkill = inRangeInclusive(rawActivity, 42, 59);

        int useSkillId = 0;
        int useSkillLevel = 0;

        if (isSkill)
        {
            // 如果怪物释放了技能

            useSkillId = skillId;
            useSkillLevel = skillLv;

            if (monster.hasSkill(useSkillId, useSkillLevel))
            {
                // 当前是属于怪物释放的技能

                // 技能类型
                MobSkillType mobSkillType = MobSkillType.from(useSkillId).orElseThrow();

                MobSkill toUse = MobSkillFactory.getMobSkillOrThrow(mobSkillType, useSkillLevel);

                if (monster.canUseSkill(toUse, true))
                {
                    // 技能生效，对玩家会产生作用。

                    int animationTime = MonsterInformationProvider.getInstance().getMobSkillAnimationTime(toUse);
                    if (animationTime > 0
                            && toUse.getType() != MobSkillType.BANISH)
                    {
                        // 按动画时长，延迟animationTime后生效技能
                        toUse.applyDelayedEffect(player, monster, true, animationTime);
                    }
                    else
                    {
                        // 立即释放技能
                        banishPlayers = new LinkedList<>();
                        toUse.applyEffect(player, monster, true, banishPlayers);
                    }
                }
            }
        }
        else
        {
            // 怪物物理攻击校验，不会在此处对玩家产生作用。
            int castPos = (rawActivity - 24) / 2;
            int atkStatus = monster.canUseAttack(castPos, isSkill); // 校验"这招是否在冷却"
            if (atkStatus < 1)
            {
                rawActivity = -1;
                pOption = 0;
            }
        }

        boolean nextMovementCouldBeSkill = !(isSkill || (pNibbles != 0));
        MobSkill nextUse = null;
        int nextSkillId = 0;
        int nextSkillLevel = 0;
        int mobMp = monster.getMp();

        if (nextMovementCouldBeSkill && monster.hasAnySkill())
        {
            // 怪物存在技能，且下一个动作可能是释放技能

            // 从怪物技能中，随机一个技能ID
            MobSkillId skillToUse = monster.getRandomSkill();

            // 下一次释放技能的技能ID和技能等级
            nextSkillId = skillToUse.type().getId();
            nextSkillLevel = skillToUse.level();

            nextUse = MobSkillFactory.getMobSkillOrThrow(skillToUse.type(), skillToUse.level());

            if (!(nextUse != null
                    && monster.canUseSkill(nextUse, false)
                    && nextUse.getHP() >= (int) (((float) monster.getHp() / monster.getMaxHp()) * 100)
                    && mobMp >= nextUse.getMpCon()))
            {
                // thanks OishiiKawaiiDesu for noticing mobs trying to cast skills they are not supposed to be able
                nextSkillId = 0;
                nextSkillLevel = 0;
                nextUse = null;
            }
        }

        p.readByte();
        p.readInt(); // whatever

        // 当前怪物，客户端所在的本次移动起始位置
        short start_x = p.readShort(); // hmm.. startpos?
        short start_y = p.readShort(); // hmm...
        Point startPos = new Point(start_x, start_y - 2);

        // 服务端记录的怪物坐标
        Point serverStartPos = new Point(monster.getPosition());

        Boolean aggro = monster.aggroMoveLifeUpdate(player);
        if (aggro == null)
        {
            return;
        }


        if (nextUse != null)
        {
            // 发送给当前协议发送者，
            // 告诉它"这次移动 OK、怪还剩多少 MP、下个动作怪可能放这个技能
            c.sendPacket(
                    PacketCreator.moveMonsterResponse(
                            objectid
                            , moveid
                            , mobMp
                            , aggro
                            , nextSkillId
                            , nextSkillLevel));
        }
        else
        {
            c.sendPacket(
                    PacketCreator.moveMonsterResponse(
                            objectid
                            , moveid
                            , mobMp
                            , aggro));
        }


        try
        {
            int movementDataStart = p.getPosition();

            // 更新怪物坐标
            updatePosition(p, monster, -2);  // Thanks Doodle & ZERO傑洛 for noticing sponge-based bosses moving out of stage in case of no-offset applied

            long movementDataLength = p.getPosition() - movementDataStart; //how many bytes were read by updatePosition
            // 更新读取节点
            p.seek(movementDataStart);

            if (GameConfig.getServerBoolean("use_debug_show_life_move"))
            {
                log.info("{} rawAct: {}, opt: {}, skillId: {}, skillLv: {}, allowSkill: {}, mobMp: {}",
                        isSkill ? "SKILL" : (isAttack ? "ATTCK" : ""), rawActivity, pOption, useSkillId,
                        useSkillLevel, nextMovementCouldBeSkill, mobMp);
            }

            map.broadcastMessage(
                    // 广播原始对象
                    player
                    // 需要被广播的协议
                    , PacketCreator.moveMonster(
                            objectid
                            , nextMovementCouldBeSkill  // 下一次可能释放技能
                            , rawActivity   //
                            , useSkillId    // 当前怪物释放的技能ID
                            , useSkillLevel // 当前怪物释放的技能等级
                            , pOption
                            , startPos
                            , p
                            , movementDataLength)
                    // 广播的坐标为当前怪物的位置附近玩家
                    , serverStartPos);

            //updatePosition(res, monster, -2); //does this need to be done after the packet is broadcast?

            // 更新地图所有玩家对怪物的移动信息
            map.moveMonster(monster, monster.getPosition());
        }
        catch (EmptyMovementException e)
        {
        }

        if (banishPlayers != null)
        {
            /**
             *  怪物释放了传送技能，
             *  需要把玩家传送处当前地图
             * **/
            for (Character chr : banishPlayers)
            {
                chr.changeMapBanish(
                        monster.getBanish().getMap()
                        , monster.getBanish().getPortal()
                        , monster.getBanish().getMsg());
            }
        }
    }

    private static boolean inRangeInclusive(Byte pVal, Integer pMin, Integer pMax) {
        return !(pVal < pMin) || (pVal > pMax);
    }
}
