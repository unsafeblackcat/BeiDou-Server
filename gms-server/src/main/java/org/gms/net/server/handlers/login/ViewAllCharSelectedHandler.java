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
package org.gms.net.server.handlers.login;

import org.gms.client.Client;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.net.server.Server;
import org.gms.net.server.coordinator.session.Hwid;
import org.gms.net.server.coordinator.session.SessionCoordinator;
import org.gms.net.server.coordinator.session.SessionCoordinator.AntiMulticlientResult;
import org.gms.net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.util.PacketCreator;
import org.gms.util.Randomizer;

import java.net.InetAddress;
import java.net.UnknownHostException;

public final class ViewAllCharSelectedHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(ViewAllCharSelectedHandler.class);

    private static int parseAntiMulticlientError(AntiMulticlientResult res) {
        return switch (res) {
            case REMOTE_PROCESSING -> 10;
            case REMOTE_LOGGEDIN -> 7;
            case REMOTE_NO_MATCH -> 17;
            case COORDINATOR_ERROR -> 8;
            default -> 9;
        };
    }

    @Override
    public final void handlePacket(InPacket p, Client c) {
        // 登录角色 cid
        int charId = p.readInt();
        p.readInt(); // please don't let the client choose which world they should login

        String macs = p.readString();
        String hostString = p.readString();

        final Hwid hwid;
        try {
            hwid = Hwid.fromHostString(hostString);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid host string: {}", hostString, e);
            c.sendPacket(PacketCreator.getAfterLoginError(17));
            return;
        }

        // 更新当前客户端的 mac
        c.updateMacs(macs);

        //更新当客户端的 hwid
        c.updateHwid(hwid);

        // 以mac 和 hwid 做封号检查 直接封机器
        if (c.hasBannedMac() || c.hasBannedHWID()) {
            SessionCoordinator.getInstance().closeSession(c, true);
            return;
        }

        // 禁止多开。通过后台 deterred_multi_client 空咋
        AntiMulticlientResult res = SessionCoordinator.getInstance().attemptGameSession(c, c.getAccID(), hwid);
        if (res != AntiMulticlientResult.SUCCESS) {
            c.sendPacket(PacketCreator.getAfterLoginError(parseAntiMulticlientError(res)));
            return;
        }

        /**
         *  检查是否在 Server.accountChars 变量中。
         *  如果不在，则表明可能通过其他方案绕过在 LOGIN_PASSWORD 的登录拦截
         * **/
        Server server = Server.getInstance();
        if (!server.haveCharacterEntry(c.getAccID(), charId)) {
            SessionCoordinator.getInstance().closeSession(c, true);
            return;
        }

        // 更新 clinet 角色所在世界
        c.setWorld(server.getCharacterWorld(charId));

        // 检查世界频道在线人数是否满足 channel_capacity 配置
        World wserv = c.getWorldServer();
        if (wserv == null || wserv.isWorldCapacityFull()) {
            c.sendPacket(PacketCreator.getAfterLoginError(10));
            return;
        }

        // 随机让角色进入一个频道
        try {
            int channel = Randomizer.rand(1, wserv.getChannelsSize());
            c.setChannel(channel);
        } catch (Exception e) {
            e.printStackTrace();
            c.setChannel(1);
        }

        String[] socket = server.getInetSocket(c, c.getWorld(), c.getChannel());
        if (socket == null) {
            c.sendPacket(PacketCreator.getAfterLoginError(10));
            return;
        }

        server.unregisterLoginState(c);
        c.setCharacterOnSessionTransitionState(charId);

        // 发送给客户端，服务器IP，频道端口，以及登录角色 cid 告知登录成功。
        try {
            c.sendPacket(PacketCreator.getServerIP(
                    InetAddress.getByName(socket[0])
                    , Integer.parseInt(socket[1])
                    , charId));
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }
}
