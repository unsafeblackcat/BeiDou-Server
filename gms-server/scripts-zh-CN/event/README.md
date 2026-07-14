# 事件脚本索引

> 本目录包含 96 个 GraalJS 事件脚本，每个对应游戏中的一种玩法。脚本通过 `em`（全局注入的 `EventManager`）与 Java 服务端双向交互，入口函数为 `init()`。

---

## ⚡ 经验活动

| 文件 | 游戏内容 |
|------|----------|
| `2xEvent.js` | **全服双倍经验活动** — 定时自动开启/关闭双倍经验倍率，玩家打怪获得的经验翻倍 |

---

## 🎯 三次转职（3rd Job Advancement）

| 文件 | 职业 | 地图 | 游戏内容 |
|------|------|------|----------|
| `3rdJob_warrior.js` | 战士 | 108010300 | 进入修炼场击败教官，20分钟限时 |
| `3rdJob_magician.js` | 魔法师 | 108010200 | 进入修炼场击败教官，20分钟限时 |
| `3rdJob_bowman.js` | 弓箭手 | 108010100 | 进入修炼场击败教官，20分钟限时 |
| `3rdJob_thief.js` | 飞侠 | 108010400 | 进入修炼场击败教官，20分钟限时 |
| `3rdJob_pirate.js` | 海盗 | 108010500 | 进入修炼场击败教官，20分钟限时 |
| `3rdJob_mount.js` | 通用 | — | 三转后获得坐骑的任务挑战 |

---

## 🎯 四次转职 / 技能任务（4th Job / Skills）

| 文件 | 游戏内容 |
|------|----------|
| `4jaerial.js` | **乔纳森的考验** — 四转前置挑战，2分钟限时 |
| `4jship.js` | **凯琳的考验** — 海盗四转挑战，含"试炼通过/考验失败"中文提示 |
| `4jsuper.js` | **凯琳的考验·上级** — 四转进阶挑战 |
| `s4aWorld.js` | **神射手技能** — 学习"狙击/神箭手之专注"技能 |

---

## 🚢 交通 / 运输（Transportation）

| 文件 | 路线 | 机制 |
|------|------|------|
| `Boats.js` | 天空之城 ↔ 魔法密林 | 定时出航/到站，航行中广播 |
| `Cabin.js` | 天空之城 ↔ 神木村 | 5分钟航程，到站自动传送 |
| `Trains.js` | 天空之城 ↔ 玩具城 | 5分钟车程，到站自动传送 |
| `Genie.js` | 天空之城 ↔ 阿里安特 | 5分钟航程 |
| `AirPlane.js` | 废弃都市 ↔ 新加坡（CBD） | 定时起降，含 closeTime/beginTime |
| `Subway.js` | 废弃都市 ↔ 新叶城（NLC） | 地铁模式，含 docked/entry 状态 |
| `KerningTrain.js` | 废弃都市 ↔ 新叶城 | 列车模式 |
| `Elevator.js` | 玩具城电梯（上下） | 含 goingUp/goingDown 状态 |
| `Hak.js` | 彩虹村鹳鸟飞行 | 飞行路线调度 |

---

## 👹 野外 BOSS（Area / Field Bosses）

> 所有野外 BOSS 采用统一模式：定时（180 分钟间隔）在指定地图生成，含中文 BOSS 名称和诗意登场公告。

| 文件 | BOSS ID | BOSS 名称 | 刷新地图 |
|------|---------|-----------|----------|
| `AreaBossMano.js` | 2220000 | 红蜗牛王 | 104000400（射手村训练场） |
| `AreaBossStumpy.js` | 3220000 | 树妖王 | 101030404 |
| `AreaBossDeo.js` | 3220001 | 大宇 | 260010201 |
| `AreaBossKingClang.js` | 5220001 | 巨居蟹 | 110040000（黄金海岸） |
| `AreaBossFaust1.js` | 5220002 | 浮士德 | 100040105 |
| `AreaBossFaust2.js` | 5220002 | 浮士德 | 100040106 |
| `AreaBossTimer1.js` | 5220003 | 提莫 | 220050000 |
| `AreaBossTimer2.js` | 5220003 | 提莫 | 220050100 |
| `AreaBossTimer3.js` | 5220003 | 提莫 | 220050200 |
| `AreaBossCentipede.js` | 5220004 | 巨型蜈蚣 | 251010102 |
| `AreaBossDyle.js` | 6220000 | 多立 | 101030300 |
| `AreaBossZeno.js` | 6220001 | 朱诺 | 221040301 |
| `AreaBossTaeRoon.js` | 7220000 | 肯德熊 | 250010304 |
| `AreaBossNineTailedFox.js` | 7220001 | 九尾狐 | 222010310 |
| `AreaBossKingSageCat.js` | 7220002 | 妖怪禅师 | 250010504 |
| `AreaBossEliza1.js` | 8220000 | 艾利杰 | 200010100 |
| `AreaBossSeruf.js` | 8220001 | 赛尔夫 | 230040000 |
| `AreaBossKimera.js` | 8220002 | 吉米拉 | 261030000 |
| `AreaBossLeviathan.js` | 8220003 | 大海兽 | 240040401 |
| `AreaBossBamboo.js` | 8220004 | 竹仙 | 250010102 |
| `AreaBossSnackBar.js` | 8220005 | 小吃摊 | 220070000 |
| `AreaBossDoor1.js` | 9400610 | 黑暗独角兽 | 677000003 |
| `AreaBossDoor2.js` | 9400609 | 印第安老斑鸠 | 677000005 |
| `AreaBossDoor3.js` | 9400613 | 沃勒福 | 677000009 |
| `AreaBossDoor4.js` | 9400633 | 牛魔王 | 677000012 |
| `AreaBossDoor5.js` | 9400612 | 牛魔王 | 677000001 |
| `AreaBossDoor6.js` | 9400611 | 雪之猫女 | 677000007 |

---

## 👹 远征 BOSS 战（Expedition Boss Battles）

| 文件 | BOSS | 人数 | 时限 | 特色 |
|------|------|------|------|------|
| `ZakumBattle.js` | 扎昆 | 6-30 人 | — | 含伤害统计和远征人数检测 |
| `HorntailBattle.js` | 暗黑龙王 | 6-30 人 | 120 分钟 | 含伤害统计 |
| `PinkBeanBattle.js` | 品克缤 | 6-30 人 | — | 含召唤物管理 |
| `BalrogBattle.js` | 巴洛古 | 6-30 人 | 60 分钟 | — |
| `BalrogBattle_Easy.js` | 简易巴洛古 | — | — | 低难度版 |
| `ScargaBattle.js` | 斯嘉加 | 6-30 人 | 60 分钟 | — |
| `PapulatusBattle.js` | 帕普拉图斯 | — | — | — |
| `LatanicaBattle.js` | 拉塔尼卡 | — | — | 含时间限制 |
| `ElementalBattle.js` | 元素死神 | 2 人 | — | 限法师职业参与 |
| `ShowaBattle.js` | 昭和 BOSS | 3-30 人 | 60 分钟 | 日本主题 BOSS |
| `MahaBattle.js` | 马哈 | — | — | — |

---

## 👹 组队 BOSS 战（Party Boss Battles）

| 文件 | 游戏内容 |
|------|----------|
| `DelliBattle.js` | **德莉 BOSS 战** — 含 friendlyKilled 检测机制 |
| `KingPepeAndYetis.js` | **企鹅王与雪吉拉** — 双 BOSS 挑战 |
| `TD_Battle1.js` | **武陵道场 1 层** — Vs 所罗门智者 |
| `TD_Battle2.js` | **武陵道场 2 层** — Vs 雷克斯智者 |
| `TD_Battle3.js` | **武陵道场 3 层** — Vs 哈金智者 |
| `TD_Battle4.js` | **武陵道场 4 层** — Vs 欧伯朗 |
| `TD_Battle5.js` | **武陵道场 5 层** — Vs 尼伯龙根 |

---

## 🏰 组队任务 PQ（Party Quests）

| 文件 | PQ 名称 | 人数 | 等级 | 时限 | 特色 |
|------|---------|------|------|------|------|
| `KerningPQ.js` | 废弃都市 PQ | 3-4 | 21-30 | 30 分钟 | 经典 PQ，4 阶段 |
| `LudiPQ.js` | 玩具城 PQ | — | — | — | 9 个阶段，丰富奖励 |
| `LudiMazePQ.js` | 玩具城迷宫 PQ | — | — | — | 多地图迷宫 |
| `OrbisPQ.js` | 天空之城 PQ | — | — | — | 6 个阶段 |
| `EllinPQ.js` | 艾琳 PQ | 4-6 | — | 30 分钟 | 冒险家专属 |
| `ElnathPQ.js` | 艾尔纳斯 PQ | 1-4 | — | 10 分钟 | 泰勒斯挑战 |
| `HenesysPQ.js` | 射手村 PQ | — | — | — | 多阶段+奖励 |
| `PiratePQ.js` | 海盗 PQ | — | — | — | 多阶段清除效果 |
| `MagatiaPQ_A.js` | 玛加提亚 PQ（艾卡德诺） | 4 | — | 45 分钟 | 罗密欧阵营 |
| `MagatiaPQ_Z.js` | 玛加提亚 PQ（杰努米斯） | 4 | — | 45 分钟 | 朱丽叶阵营 |
| `CafePQ_1.js` | 咖啡 PQ 第 1 关 | 3-6 | — | 45 分钟 | 收集 400 券 |
| `CafePQ_2.js` | 咖啡 PQ 第 2 关 | 3-6 | — | 45 分钟 | 收集 350 券 |
| `CafePQ_3.js` | 咖啡 PQ 第 3 关 | 3-6 | — | 45 分钟 | 收集 350 券 |
| `CafePQ_4.js` | 咖啡 PQ 第 4 关 | 3-6 | — | 45 分钟 | 收集 450 券 |
| `CafePQ_5.js` | 咖啡 PQ 第 5 关 | 3-6 | — | 45 分钟 | — |
| `CafePQ_6.js` | 咖啡 PQ 第 6 关 | 3-6 | — | 45 分钟 | — |
| `HolidayPQ_1.js` | 假期 PQ 第 1 关 | 3-6 | 20-30 | — | 含雪人进化机制 |
| `HolidayPQ_2.js` | 假期 PQ 第 2 关 | 3-6 | 31-40 | — | 含雪人进化机制 |
| `HolidayPQ_3.js` | 假期 PQ 第 3 关 | 3-6 | 41-50 | — | 含雪人进化机制 |
| `AmoriaPQ.js` | 阿莫里亚 PQ | 6 | — | 75 分钟 | 仅限已婚玩家参与 |
| `BossRushPQ.js` | BOSS 冲冲冲 | — | — | — | 多阶段 BOSS 连战 |
| `CWKPQ.js` | 绯红要塞 PQ | 4-30 | — | — | 远征型 PQ |
| `TreasurePQ.js` | 寻宝 PQ | 4-6 | 140+ | 45+10 分钟 | 含奖励阶段 |
| `GuildQuest.js` | 公会任务 | — | — | — | 多阶段+守护 NPC |
| `ZakumPQ.js` | 扎昆前置 PQ | 6 | — | 30 分钟 | 进入扎昆远征的前置任务 |
| `HorntailPQ.js` | 龙王前置 PQ | 6 | — | 25 分钟 | 进入龙王远征的前置任务 |
| `WuGongPQ.js` | 悟空 PQ | — | — | — | — |
| `YaoSengPQ.js` | 妖精 PQ | — | — | — | — |

---

## 🎪 任务 / 事件（Quest Events）

| 文件 | 游戏内容 |
|------|----------|
| `MK_PrimeMinister.js` | **蘑菇王国首相战** — 品克缤前置 BOSS 任务，召唤 BOSS 3300008 |
| `MK_PrimeMinister2.js` | **蘑菇王国首相战 2** — 同上第二张地图 |
| `RescueGaga.js` | **营救嘎嘎** — 单人任务，3 分钟限时 |
| `DollHouse.js` | **人偶之家** — 10 分钟限时，收集物品 4031094 |
| `Puppeteer.js` | **傀儡师** — 1 分钟限时 BOSS 挑战 |
| `q3239.js` | **任务 3239** — 人偶之家相关单人任务，20 分钟 |
| `Cygnus_Magic_Library.js` | **希纳斯魔法图书馆** — 希纳斯相关任务 |
| `BalrogQuest.js` | **巴洛古任务** — 与巴洛古远征相关的前置/任务 |

---

## 🏔️ 修炼场（Training / Grinding）

| 文件 | 游戏内容 |
|------|----------|
| `RockSpirit.js` | **石灵修炼场** — 60 分钟刷怪场，持续刷新怪物供练级 |
| `RockSpiritVIP.js` | **石灵 VIP 修炼场** — 30 分钟 VIP 版，不同地图 |

---

## 🏇 坐骑 / 其他（Mounts & Miscellaneous）

| 文件 | 游戏内容 |
|------|----------|
| `Aran_2ndmount.js` | **阿兰二阶坐骑** — Scadur's Mount Quest，3 分钟限时 |
| `Aran_3rdmount.js` | **阿兰三阶坐骑** — 阿兰坐骑进阶任务 |
| `GuardianNex.js` | **守护者涅克斯** — 多阶段事件 |
| `NineSpirit.js` | **九灵事件** — 与九灵相关的任务 |

---

## 💒 婚礼系统（Wedding）

| 文件 | 游戏内容 |
|------|----------|
| `WeddingCathedral.js` | **大教堂婚礼** — Premium 婚礼，含集合→仪式→祝福→派对四阶段，可召唤蛋糕 BOSS |
| `WeddingChapel.js` | **小教堂婚礼** — 标准婚礼，流程与大教堂类似 |

---

## 📄 模板

| 文件 | 说明 |
|------|------|
| `0_EXAMPLE.js` | 事件脚本开发模板，列出所有可用回调函数及中文注释 |

---

## 🔧 技术说明

- **引擎：** GraalJS（`polyglot.js.allowHostAccess` / `allowHostClassLookup` 已开启）
- **入口：** `engine.eval(br)` 仅声明变量和函数签名；`invokeFunction("init")` 才真正执行业务逻辑
- **注入对象：** `em`（`EventManager`），JS 可直接调用其所有 public 方法
- **回调函数：** `setup`、`playerEntry`、`monsterKilled`、`scheduledTimeout`、`clearPQ`、`dispose` 等 20+ 个生命周期回调由 Java 服务端在相应时机主动触发
