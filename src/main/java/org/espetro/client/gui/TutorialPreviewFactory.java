package org.espetro.client.gui;

import net.minecraft.client.gui.screens.Screen;
import org.espetro.network.ClassSelectScreenPacket;
import org.espetro.network.MapVoteStatePacket;
import org.espetro.tutorial.TutorialStep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 教程用演示数据屏幕工厂。数据为客户端静态 fixture，不碰真实 GameState。
 */
final class TutorialPreviewFactory {

    private TutorialPreviewFactory() {
    }

    static Screen create(TutorialStep step) {
        if (step == null) {
            return null;
        }
        return switch (step) {
            case WELCOME, HUB -> new HubScreen(0, "教程预览 · 主城");
            case MAP_VOTE -> mapVotePreview();
            case MAP_LOADING -> null; // 仅 HUD
            case TEAM_SELECT -> new TeamSelectionScreen();
            case COMMANDER_VOTE -> new CommanderVoteScreen(
                "DEFEND",
                List.of("Alpha", "Bravo", "Charlie", "Delta"),
                20,
                org.espetro.team.TeamDisplayNames.displayName("ATTACK"),
                "",
                0);
            case FACTION_SELECT -> classSelectPreview();
            case FACTION_REVEAL -> new FactionRevealScreen("示范进攻编制", "示范防守编制", null, null, 30);
            case DEPLOY_PANEL, SQUAD, CLASS_SELECT, DEPLOY_POINT -> null; // 无全量 packet 时仅 HUD
            case KEYS_RADIAL, RADIO_RALLY, LOGISTICS_FOB, COMMANDER_SKILLS,
                 OUTPOST, BATTLE, RESPAWN, SCORE_ROUND, MID_JOIN -> null;
        };
    }

    private static Screen mapVotePreview() {
        List<MapVoteStatePacket.Candidate> candidates = new ArrayList<>();
        candidates.add(new MapVoteStatePacket.Candidate("demo_alpha", "示范地图 A", "平坦训练场"));
        candidates.add(new MapVoteStatePacket.Candidate("demo_bravo", "示范地图 B", "城市巷战"));
        candidates.add(new MapVoteStatePacket.Candidate("demo_charlie", "示范地图 C", "山地防线"));
        Map<String, Integer> tally = new HashMap<>();
        tally.put("demo_alpha", 2);
        tally.put("demo_bravo", 1);
        tally.put("demo_charlie", 0);
        MapVoteStatePacket packet = new MapVoteStatePacket(
            true, 25, 0L, candidates, tally, null, null, null);
        MapVoteScreen.update(packet);
        return new MapVoteScreen();
    }

    private static Screen classSelectPreview() {
        List<ClassSelectScreenPacket.FactionInfo> factions = new ArrayList<>();
        factions.add(new ClassSelectScreenPacket.FactionInfo(
            "demo_light", "轻装示范编制", "", 1));
        factions.add(new ClassSelectScreenPacket.FactionInfo(
            "demo_heavy", "重装示范编制", "", 0));
        factions.add(new ClassSelectScreenPacket.FactionInfo(
            "demo_mech", "机械化示范编制", "", 0));
        return new ClassSelectScreen(
            "DEFEND", true, factions, 30,
            org.espetro.team.TeamDisplayNames.displayName("ATTACK"), "", -1, null);
    }
}
