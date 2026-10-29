package me.bytefox.livingcolonists.talk;

/**
 * Who a colonist is: shown as the chat name, with profession and colony on hover.
 *
 * @param job               profession in English, for the prompt
 * @param jobTranslationKey MineColonies translation key of the profession, for players; null if unemployed
 * @param colonyId          with {@code citizenId}, identifies the colonist even when its entity isn't loaded
 */
public record Speaker(String name, String job, String jobTranslationKey, String colony, int colonyId, int citizenId) {
}
