package me.bytefox.livingcolonists.talk;

import net.minecraft.ChatFormatting;

/**
 * Who a colonist is: shown as the chat name, with profession and colony on hover.
 *
 * @param job               profession in English, for the prompt
 * @param jobTranslationKey MineColonies translation key of the profession, for players; null if unemployed
 * @param colonyId          with {@code citizenId}, identifies the colonist even when its entity isn't loaded
 * @param color             the colony's team color, which the name is shown in
 */
public record Speaker(String name, String job, String jobTranslationKey, String colony, int colonyId, int citizenId,
                      ChatFormatting color) {
}
