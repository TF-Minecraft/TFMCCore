package net.tfminecraft.tfmccore.stats.categories.skills;

import java.util.Locale;
import java.util.UUID;

import io.lumine.mythic.lib.api.event.skill.SkillCastEvent;
import io.lumine.mythic.lib.skill.Skill;
import io.lumine.mythic.lib.skill.handler.SkillHandler;
import io.lumine.mythic.lib.skill.trigger.TriggerType;
import net.tfminecraft.tfmccore.stats.StatManager;

public final class SkillsStatMain {
    private static final String CATEGORY_ID = "skills";
    private static final String SKILL_KEY_PREFIX = "skill_";

    public void handle(SkillCastEvent event) {
        if (!StatManager.isInitialized()) {
            return;
        }

        Skill cast = event.getCast();
        if (cast == null) {
            return;
        }

        // Preserve the legacy CAST/API statistics filter; metadata triggers would change counted casts.
        @SuppressWarnings("deprecation")
        TriggerType trigger = cast.getTrigger();
        if (trigger != TriggerType.CAST && trigger != TriggerType.API) {
            return;
        }

        String skillId = resolveSkillId(cast);
        if (skillId == null || skillId.isBlank()) {
            return;
        }

        UUID playerUuid = event.getPlayer().getUniqueId();
        String statKey = SKILL_KEY_PREFIX + skillId.toLowerCase(Locale.ROOT);
        StatManager.getInstance().increment(playerUuid, CATEGORY_ID, statKey, 1L);
    }

    // MMOCore's ClassSkill/RegisteredSkill accessors move between builds, so key off the
    // MythicLib handler id instead: it is the stable skill identifier and needs no MMOCore API.
    private static String resolveSkillId(Skill cast) {
        SkillHandler<?> handler = cast.getHandler();
        if (handler != null) {
            return handler.getLowerCaseId();
        }

        return null;
    }
}
