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
package org.gms.server.maps;

import org.gms.config.GameConfig;
import org.gms.constants.id.MapId;
import org.gms.provider.*;
import org.gms.provider.wz.WZFiles;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.life.AbstractLoadedLife;
import org.gms.server.life.LifeFactory;
import org.gms.server.life.Monster;
import org.gms.server.life.PlayerNPC;
import org.gms.server.partyquest.GuardianSpawnPoint;
import org.gms.util.DatabaseConnection;
import org.gms.util.NumberTool;
import org.gms.util.StringUtil;

import java.awt.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;

public class MapFactory {
    private static final Data nameData = DataProviderFactory.getDataProvider(WZFiles.STRING).getData("Map.img");
    private static final DataProvider mapSource = DataProviderFactory.getDataProvider(WZFiles.MAP);

    private static void loadLifeFromWz(MapleMap map, Data mapData)
    {
        for (Data life : mapData.getChildByPath("life"))
        {
            life.getName();

            // 怪物ID
            String id = DataTool.getString(life.getChildByPath("id"));

            // 怪物类型
            String type = DataTool.getString(life.getChildByPath("type"));

            // 特殊地图，怪物阵营
            int team = DataTool.getInt("team", life, -1);

            if (map.isCPQMap2() && type.equals("m"))
            {
                // 是指定地图且是怪物
                if ((Integer.parseInt(life.getName()) % 2) == 0)
                {
                    team = 0;
                }
                else
                {
                    team = 1;
                }
            }

            // 碰撞框高度
            int cy = DataTool.getInt(life.getChildByPath("cy"));

            // 朝向方位
            Data dF = life.getChildByPath("f");
            int f = (dF != null) ? DataTool.getInt(dF) : 0;

            // 所在平台（foothold 编号，生物站的地形）
            int fh = DataTool.getInt(life.getChildByPath("fh"));

            // 生物活动范围左右边界（怪物巡逻用，NPC不消费）
            int rx0 = DataTool.getInt(life.getChildByPath("rx0"));
            int rx1 = DataTool.getInt(life.getChildByPath("rx1"));

            // 出生 XY 坐标
            int x = DataTool.getInt(life.getChildByPath("x"));
            int y = DataTool.getInt(life.getChildByPath("y"));

            // 是否隐藏
            int hide = DataTool.getInt("hide", life, 0);

            // 重生间隔，-1=不重生
            int mobTime = DataTool.getInt("mobTime", life, 0);

            loadLifeRaw(map
                    , Integer.parseInt(id)
                    , type
                    , cy
                    , f
                    , fh
                    , rx0
                    , rx1
                    , x
                    , y
                    , hide
                    , mobTime
                    , team);
        }
    }

    private static void loadLifeFromDb(MapleMap map)
    {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT * FROM plife WHERE map = ? and world = ?"))
        {
            ps.setInt(1, map.getId());
            ps.setInt(2, map.getWorld());

            try (ResultSet rs = ps.executeQuery())
            {
                while (rs.next())
                {
                    int id = rs.getInt("life");
                    String type = rs.getString("type");
                    int cy = rs.getInt("cy");
                    int f = rs.getInt("f");
                    int fh = rs.getInt("fh");
                    int rx0 = rs.getInt("rx0");
                    int rx1 = rs.getInt("rx1");
                    int x = rs.getInt("x");
                    int y = rs.getInt("y");
                    int hide = rs.getInt("hide");
                    int mobTime = rs.getInt("mobtime");
                    int team = rs.getInt("team");

                    loadLifeRaw(map, id, type, cy, f, fh, rx0, rx1, x, y, hide, mobTime, team);
                }
            }
        }
        catch (SQLException sqle)
        {
            sqle.printStackTrace();
        }
    }

    private static void loadLifeRaw(
            MapleMap map
            , int id
            , String type
            , int cy
            , int f
            , int fh
            , int rx0
            , int rx1
            , int x
            , int y
            , int hide
            , int mobTime
            , int team)
    {
        // 生成怪物或NPC内容
        AbstractLoadedLife myLife = loadLife(id, type, cy, f, fh, rx0, rx1, x, y, hide);

        if (myLife instanceof Monster monster)
        {
            // 如果是怪物类型则转换为 monster

            // 怪物生成倍率
            int mobRespawnRate = GameConfig.getServerInt("mob_respawn_rate");

            // BOSS刷新时间速率
            float mobTimeRate = GameConfig.getServerFloat("boss_respawn_mob_time_rate");

            mobTimeRate = (mobTimeRate <= 0 || mobTimeRate > 1) ? 1 : mobTimeRate;  //将值限定在0~1之间的范围

            if (mobRespawnRate < 1)
            {
                //如果 mob_respawn_rate 读入的值小于1
                mobRespawnRate = 1;
            }

            if (monster.isBoss())
            {
                //怪物为boss

                mobRespawnRate = 1;
                mobTime = NumberTool.floatToInt(mobTime * mobTimeRate);
            }

            if (map.getEventInstance() != null)
            {
                // 如果是事件地图，怪物生成倍率强制为1
                mobRespawnRate = 1;
            }

            for (int i = 0; i < mobRespawnRate; i++)
            {
                if (mobTime == -1)
                {
                    // 只会生成一次的怪物
                    //does not respawn, force spawn once
                    map.spawnMonster(monster);
                }
                else
                {
                    map.addMonsterSpawn(monster, mobTime, team);
                }
            }

            // 同时登记进 allMonsterSpawn 完整列表（地图重置时用它恢复刷怪）
            map.addAllMonsterSpawn(monster, mobTime, team);
        }
        else
        {
            // NPC
            map.addMapObject(myLife);
        }
    }

    public static MapleMap loadMapFromWz(int mapid, int world, int channel, EventInstanceManager event) {
        MapleMap map;

        // 通过ID得到地图名称
        String mapName = getMapName(mapid);

        Data mapData = mapSource.getData(mapName);    // source.getData issue with giving nulls in rare ocasions found thanks to MedicOP

        Data infoData = mapData.getChildByPath("info");

        String link = DataTool.getString(infoData.getChildByPath("link"), "");
        if (!link.equals(""))
        { //nexon made hundreds of dojo maps so to reduce the size they added links.
            mapName = getMapName(Integer.parseInt(link));
            mapData = mapSource.getData(mapName);
        }

        float monsterRate = 0;

        // 怪物生成倍率
        Data mobRate = infoData.getChildByPath("mobRate");
        if (mobRate != null)
        {
            monsterRate = (Float) mobRate.getData();
        }

        map = new MapleMap(
                mapid
                , world
                , channel
                , DataTool.getInt("returnMap", infoData)
                , monsterRate);

        // 地图触发事件
        map.setEventInstance(event);

        // 首个玩家进入触发的地图脚本名
        String onFirstEnter = DataTool.getString(infoData.getChildByPath("onFirstUserEnter")
                , String.valueOf(mapid));
        map.setOnFirstUserEnter(
                onFirstEnter.equals("") ? String.valueOf(mapid) : onFirstEnter);

        // 每次玩家进入触发的地图脚本名
        String onEnter = DataTool.getString(infoData.getChildByPath("onUserEnter")
                , String.valueOf(mapid));
        map.setOnUserEnter(onEnter.equals("") ? String.valueOf(mapid) : onEnter);

        // 地图限制位掩码
        map.setFieldLimit(DataTool.getInt(infoData.getChildByPath("fieldLimit"), 0));

        // 怪物重生检查间隔（毫秒）
        map.setMobInterval((short) DataTool.getInt(infoData.getChildByPath("createMobInterval")
                        , 5000));

        PortalFactory portalFactory = new PortalFactory();

        // 地图传送门
        for (Data portal : mapData.getChildByPath("portal"))
        {
            map.addPortal(
                    portalFactory.makePortal(
                            DataTool.getInt(portal.getChildByPath("pt")), portal));
        }

        // 定时出没的稀有怪物 + 提示语
        // 只有特定的地图才有，猪猪海岸又或者蘑菇王地图也没有
        // 但是废都地铁站地图中有 103000105.img.xml
        // 内容看起来很简单，击杀特定的怪物然后弹出特定的消息。
        // 但我测试赶紧好像没啥用
        Data timeMob = infoData.getChildByPath("timeMob");
        if (timeMob != null)
        {
            map.setTimeMob(
                    DataTool.getInt(timeMob.getChildByPath("id"))
                    , DataTool.getString(timeMob.getChildByPath("message")));
        }


        // 视野矩形上下边界
        int[] bounds = new int[4];
        bounds[0] = DataTool.getInt(infoData.getChildByPath("VRTop"));
        bounds[1] = DataTool.getInt(infoData.getChildByPath("VRBottom"));

        /**
         *
         *  地图坐标分为两个版本
         *  1. 新版本：地图活动区域由 VRTop, VRBottom, VRLeft, VRRight 定义
         *  VRLeft/VRRight = 玩家活动范围的左右边界（同时是客户端镜头滚动边界），
         *  VRTop/VRBottom = 上下边界。
         *
         *  原点（0,0）是地图制作者定的固定参考点，并非指的地图中心点不是中心。
         *
         *  原点 X 轴, 向左为负数，向右为正数
         *  原点 Y 轴, 向上为负数，向下为正数
         *
         *  2. 旧版本，没有新版本字段，而是由height, width, centerX, centerY
         *  左边界 = -centerX（如 -746）
         *  右边界 = -centerX + width（= -746 + 3433 = 2687）
         *  上边界 = -centerY（如 -237）
         *  下边界 = -centerY + height（= -237 + 1271 = 1034）
         *
         * **/

        if (bounds[0] == bounds[1])
        {
            // 如果地图中没有这两个节点，那么以为使用旧的方式。数据在 miniMap 中获取
            // old-style baked map
            Data minimapData = mapData.getChildByPath("miniMap");
            if (minimapData != null)
            {
                // 地图中心点 X
                bounds[0] = DataTool.getInt(minimapData.getChildByPath("centerX")) * -1;
                // 地图中心点 Y
                bounds[1] = DataTool.getInt(minimapData.getChildByPath("centerY")) * -1;
                // 地图实际高度
                bounds[2] = DataTool.getInt(minimapData.getChildByPath("height"));
                // 地图实际宽度
                bounds[3] = DataTool.getInt(minimapData.getChildByPath("width"));

                map.setMapPointBoundings(bounds[0], bounds[1], bounds[2], bounds[3]);
            }
            else
            {
                // 旧数据有异常连minimap节点都没有。
                int dist = (1 << 18);
                map.setMapPointBoundings(-dist / 2, -dist / 2, dist, dist);
            }
        }
        else
        {
            // 视野矩形左右边界
            bounds[2] = DataTool.getInt(infoData.getChildByPath("VRLeft"));
            bounds[3] = DataTool.getInt(infoData.getChildByPath("VRRight"));

            map.setMapLineBoundings(bounds[0], bounds[1], bounds[2], bounds[3]);
        }

        List<Foothold> allFootholds = new LinkedList<>();
        Point lBound = new Point();
        Point uBound = new Point();

        // 玩家角色可站立平台落脚点
        /**
         *
         *  原点（0,0）是地图制作者定的固定参考点，并非地图中心点。
         *
         *  以地图ID 20000 为例子。foothold 有两个节点 "0" 和 "1"
         *  忽略 0节点从 1 节点开始看。
         *  在 1节点下的子节点树，首先我们需要找到  <int name="prev" value="0"/> 的数据id=14
         *  每个节点ID实际表示为一个 “站立点线段”。
         *  14: (-270,275)→(-180,275). 线段坐标x坐标 -270~-180. y坐标275
         *  <int name="next" value="15"/>, 下一个落脚点节点由 ID15来定义
         *  15: (-180,275)→(-90,275). 线段坐标x坐标 -180~-90. y坐标275
         *  <int name="next" value="17"/>, 下一个落脚点节点由 ID17来定义
         *  17: (-90,275)→(0,275). 线段坐标x坐标 -90~0. y坐标275
         *  <int name="next" value="22"/>, 下一个落脚点节点由 ID22来定义
         *  22: (0,275)→(90,215). 线段坐标x坐标 0~90. y坐标 275~215
         *  <int name="next" value="12"/>, 下一个落脚点节点由 ID12来定义
         *  12: (90,215)→(180,215). 线段坐标x坐标 90~180. y坐标 215
         *  <int name="next" value="13"/>, 下一个落脚点节点由 ID13来定义
         *  13/18-21/11/16: (180,215)→(810,215). 线段坐标x坐标 180~810. y坐标 215
         *  <int name="next" value="0"/>, 当前角色站立点结束。
         *
         *
         * **/
        for (Data footRoot : mapData.getChildByPath("foothold"))
        {
            for (Data footCat : footRoot)
            {
                for (Data footHold : footCat)
                {
                    int x1 = DataTool.getInt(footHold.getChildByPath("x1"));
                    int y1 = DataTool.getInt(footHold.getChildByPath("y1"));
                    int x2 = DataTool.getInt(footHold.getChildByPath("x2"));
                    int y2 = DataTool.getInt(footHold.getChildByPath("y2"));

                    Foothold fh = new Foothold(new Point(x1, y1), new Point(x2, y2), Integer.parseInt(footHold.getName()));
                    fh.setPrev(DataTool.getInt(footHold.getChildByPath("prev")));
                    fh.setNext(DataTool.getInt(footHold.getChildByPath("next")));
                    if (fh.getX1() < lBound.x)
                    {
                        lBound.x = fh.getX1();
                    }

                    if (fh.getX2() > uBound.x)
                    {
                        uBound.x = fh.getX2();
                    }

                    if (fh.getY1() < lBound.y)
                    {
                        lBound.y = fh.getY1();
                    }

                    if (fh.getY2() > uBound.y)
                    {
                        uBound.y = fh.getY2();
                    }
                    allFootholds.add(fh);
                }
            }
        }

        FootholdTree fTree = new FootholdTree(lBound, uBound);
        for (Foothold fh : allFootholds)
        {
            fTree.insert(fh);
        }

        map.setFootholds(fTree);

        if (mapData.getChildByPath("area") != null)
        {
            for (Data area : mapData.getChildByPath("area"))
            {
                int x1 = DataTool.getInt(area.getChildByPath("x1"));
                int y1 = DataTool.getInt(area.getChildByPath("y1"));
                int x2 = DataTool.getInt(area.getChildByPath("x2"));
                int y2 = DataTool.getInt(area.getChildByPath("y2"));
                map.addMapleArea(new Rectangle(x1, y1, (x2 - x1), (y2 - y1)));
            }
        }

        if (mapData.getChildByPath("seat") != null)
        {
            int seats = mapData.getChildByPath("seat").getChildren().size();
            map.setSeats(seats);
        }

        if (event == null)
        {
            // 地图没有事件信息
            PlayerNPC.addPlayerNPCMapObject(map);
        }

        // 地图怪物生成
        loadLifeFromWz(map, mapData);
        loadLifeFromDb(map);

        if (map.isCPQMap())
        {
            // 特定地图分支

            Data mcData = mapData.getChildByPath("monsterCarnival");
            if (mcData != null)
            {
                map.setDeathCP(DataTool.getIntConvert("deathCP", mcData, 0));
                map.setMaxMobs(DataTool.getIntConvert("mobGenMax", mcData, 20));    // thanks Atoot for noticing CPQ1 bf. 3 and 4 not accepting spawns due to undefined limits, Lame for noticing a need to cap mob spawns even on such undefined limits
                map.setTimeDefault(DataTool.getIntConvert("timeDefault", mcData, 0));
                map.setTimeExpand(DataTool.getIntConvert("timeExpand", mcData, 0));
                map.setMaxReactors(DataTool.getIntConvert("guardianGenMax", mcData, 16));
                Data guardianGenData = mcData.getChildByPath("guardianGenPos");

                for (Data node : guardianGenData.getChildren())
                {
                    GuardianSpawnPoint pt = new GuardianSpawnPoint(new Point(DataTool.getIntConvert("x", node), DataTool.getIntConvert("y", node)));
                    pt.setTeam(DataTool.getIntConvert("team", node, -1));
                    pt.setTaken(false);
                    map.addGuardianSpawnPoint(pt);
                }

                if (mcData.getChildByPath("skill") != null)
                {
                    for (Data area : mcData.getChildByPath("skill"))
                    {
                        map.addSkillId(DataTool.getInt(area));
                    }
                }

                if (mcData.getChildByPath("mob") != null)
                {
                    for (Data area : mcData.getChildByPath("mob"))
                    {
                        map.addMobSpawn(
                                DataTool.getInt(area.getChildByPath("id"))
                                , DataTool.getInt(area.getChildByPath("spendCP")));
                    }
                }
            }
        }

        if (mapData.getChildByPath("reactor") != null)
        {
            /**
             *  反应堆? 地图可交互触发点
             *  比如, 地图中可以被角色攻击的“箱子”, 魔法密林的“植物节点”,废弃都市地铁中的“路灯”等
             * **/
            for (Data reactor : mapData.getChildByPath("reactor"))
            {
                /**
                 *  反应堆 ID（对应 Reactor.wz/0002001.img 的定义）
                 *  reactorTime: 破坏后再次生成时间。秒
                 *  f: 方位朝向
                 *  name: 实例名称
                 * **/
                String id = DataTool.getString(reactor.getChildByPath("id"));
                if (id != null)
                {
                    Reactor newReactor = loadReactor(
                            reactor
                            , id
                            , (byte) DataTool.getInt(
                                    reactor.getChildByPath("f")
                                    , 0)
                    );

                    map.spawnReactor(newReactor);
                }
            }
        }

        // 地图名字
        map.setMapName(loadPlaceName(mapid));

        // 区域名
        map.setStreetName(loadStreetName(mapid));

        // 是否有地图时钟显示
        map.setClock(mapData.getChildByPath("clock") != null);
        // 是否永不消失（不执行地图物品过期清理）
        map.setEverlast(DataTool.getIntConvert("everlast", infoData, 0) != 0); // thanks davidlafriniere for noticing value 0 accounting as true

        // 是否城镇地图
        map.setTown(DataTool.getIntConvert("town", infoData, 0) != 0);

        // 环境伤害（每秒扣 HP，如火山/毒区）
        map.setHPDec(DataTool.getIntConvert("decHP", infoData, 0));
        // 环境伤害保护道具 ID（护身符）
        map.setHPDecProtect(DataTool.getIntConvert("protectItem", infoData, 0));
        // 强制返回地图 ID
        map.setForcedReturnMap(DataTool.getInt(infoData.getChildByPath("forcedReturn"), MapId.NONE));
        // 是否有船（飞行船靠岸动画）
        map.setBoat(mapData.getChildByPath("shipObj") != null);
        // 地图时间限制（秒，倒计时结束强制传回）
        map.setTimeLimit(DataTool.getIntConvert("timeLimit", infoData, -1));
        // 地图类型编号
        map.setFieldType(DataTool.getIntConvert("fieldType", infoData, 0));
        // 地图怪物容量上限
        map.setMobCapacity(DataTool.getIntConvert("fixedMobCapacity", infoData, 500));//Is there a map that contains more than 500 mobs?

        Data recData = infoData.getChildByPath("recovery");
        if (recData != null)
        {
            // 坐椅/床的 HP/MP 恢复倍率
            map.setRecovery(DataTool.getFloat(recData));
        }

        HashMap<Integer, Integer> backTypes = new HashMap<>();
        try
        {
            // 背景层，服务端只读 type（其余忽略）
            for (Data layer : mapData.getChildByPath("back"))
            {
                // yolo
                int layerNum = Integer.parseInt(layer.getName());
                int btype = DataTool.getInt(layer.getChildByPath("type"), 0);

                backTypes.put(layerNum, btype);
            }

        }
        catch (Exception e)
        {
            e.printStackTrace();
            // swallow cause I'm cool
        }

        map.setBackgroundTypes(backTypes);

        /**
         *  设置计算，地图物品掉落点范围缓存
         * **/
        map.generateMapDropRangeCache();

        return map;
    }

    private static AbstractLoadedLife loadLife(
            int id
            , String type
            , int cy
            , int f
            , int fh
            , int rx0
            , int rx1
            , int x
            , int y
            , int hide)
    {
        AbstractLoadedLife myLife = LifeFactory.getLife(id, type);

        // 碰撞框高度
        myLife.setCy(cy);
        myLife.setF(f);
        // 所在平台（foothold 编号，生物站的地形）
        myLife.setFh(fh);
        // 活动范围左右边界（怪物巡逻区间）
        myLife.setRx0(rx0);
        myLife.setRx1(rx1);

        // 出生坐标
        myLife.setPosition(new Point(x, y));
        if (hide == 1)
        {
            myLife.setHide(true);
        }
        return myLife;
    }

    private static Reactor loadReactor(Data reactor, String id, final byte FacingDirection)
    {
        Reactor myReactor = new Reactor(ReactorFactory.getReactor(Integer.parseInt(id)), Integer.parseInt(id));
        int x = DataTool.getInt(reactor.getChildByPath("x"));
        int y = DataTool.getInt(reactor.getChildByPath("y"));
        myReactor.setFacingDirection(FacingDirection);

        // 生成坐标
        myReactor.setPosition(new Point(x, y));
        myReactor.setDelay((int) SECONDS.toMillis(DataTool.getInt(reactor.getChildByPath("reactorTime"))));
        myReactor.setName(DataTool.getString(reactor.getChildByPath("name"), ""));
        myReactor.resetReactorActions(0);
        return myReactor;
    }

    private static String getMapName(int mapid)
    {
        String mapName = StringUtil.getLeftPaddedStr(Integer.toString(mapid), '0', 9);
        StringBuilder builder = new StringBuilder("Map/Map");

        int area = mapid / 100000000;
        builder.append(area);
        builder.append("/");
        builder.append(mapName);
        builder.append(".img");

        mapName = builder.toString();
        return mapName;
    }

    private static String getMapStringName(int mapid) {
        StringBuilder builder = new StringBuilder();
        if (mapid < 100000000) {
            builder.append("maple");
        } else if (mapid >= 100000000 && mapid < MapId.ORBIS) {
            builder.append("victoria");
        } else if (mapid >= MapId.ORBIS && mapid < MapId.ELLIN_FOREST) {
            builder.append("ossyria");
        } else if (mapid >= MapId.ELLIN_FOREST && mapid < 400000000) {
            builder.append("elin");
        } else if (mapid >= MapId.SINGAPORE && mapid < 560000000) {
            builder.append("singapore");
        } else if (mapid >= MapId.NEW_LEAF_CITY && mapid < 620000000) {
            builder.append("MasteriaGL");
        } else if (mapid >= 677000000 && mapid < 677100000) {
            builder.append("Episode1GL");
        } else if (mapid >= 670000000 && mapid < 682000000) {
            if ((mapid >= 674030000 && mapid < 674040000) || (mapid >= 680100000 && mapid < 680200000)) {
                builder.append("etc");
            } else {
                builder.append("weddingGL");
            }
        } else if (mapid >= 682000000 && mapid < 683000000) {
            builder.append("HalloweenGL");
        } else if (mapid >= 683000000 && mapid < 684000000) {
            builder.append("event");
        } else if (mapid >= MapId.MUSHROOM_SHRINE && mapid < 900000000) {
            if ((mapid >= 889100000 && mapid < 889200000)) {
                builder.append("etc");
            } else {
                builder.append("jp");
            }
        } else {
            builder.append("etc");
        }
        builder.append("/").append(mapid);
        return builder.toString();
    }

    public static String loadPlaceName(int mapid) {
        try {
            return DataTool.getString("mapName", nameData.getChildByPath(getMapStringName(mapid)), "");
        } catch (Exception e) {
            return "";
        }
    }

    public static String loadStreetName(int mapid) {
        try {
            return DataTool.getString("streetName", nameData.getChildByPath(getMapStringName(mapid)), "");
        } catch (Exception e) {
            return "";
        }
    }

    public static String getMapIdByLifeId(int lifeId) {
        return resolveDir(mapSource.getRoot(), lifeId);
    }

    private static String resolveDir(DataEntry dataEntry, int lifeId) {
        String mapId = null;
        if (dataEntry instanceof DataFileEntry) {
            mapId = resolveFile(dataEntry, lifeId);
        } else if (dataEntry instanceof DataDirectoryEntry) {
            List<DataFileEntry> fileEntries = ((DataDirectoryEntry) dataEntry).getFiles();
            for (DataFileEntry fileEntry : fileEntries) {
                mapId = resolveFile(fileEntry, lifeId);
                if (mapId != null) {
                    break;
                }
            }
            List<DataDirectoryEntry> subdirectories = ((DataDirectoryEntry) dataEntry).getSubdirectories();
            for (DataDirectoryEntry subdirectory : subdirectories) {
                if (!subdirectory.getName().startsWith("Map")) {
                    continue;
                }
                mapId = resolveDir(subdirectory, lifeId);
                if (mapId != null) {
                    break;
                }
            }
        }
        return mapId;
    }

    private static String resolveFile(DataEntity dataEntry, int lifeId) {
        String mapId = null;
        if (dataEntry instanceof DataFileEntry) {
            StringBuilder pathBuilder = new StringBuilder();
            resolvePath(dataEntry, pathBuilder);
            pathBuilder.append(dataEntry.getName());
            Data data = mapSource.getData(pathBuilder.toString());
            String wzLifeId = resolveFile(data, lifeId);
            if (wzLifeId != null) {
                mapId = dataEntry.getName().substring(0, dataEntry.getName().length() - 4);
            }
        } else if (dataEntry instanceof Data) {
            Data life = ((Data) dataEntry).getChildByPath("life");
            if (life == null) {
                return null;
            }
            List<Data> children = life.getChildren();
            for (Data child : children) {
                String wzLifeId = DataTool.getString("id", child);
                if (wzLifeId != null && Integer.parseInt(wzLifeId) == lifeId) {
                    return wzLifeId;
                }
            }
        }
        return mapId;
    }

    private static void resolvePath(DataEntity dataEntry, StringBuilder pathBuilder) {
        DataEntity parent = dataEntry.getParent();
        if (parent != null && parent != mapSource.getRoot()) {
            pathBuilder.insert(0, parent.getName() + "/");
            resolvePath(parent, pathBuilder);
        }
    }
}
