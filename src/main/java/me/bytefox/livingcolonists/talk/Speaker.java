package me.bytefox.livingcolonists.talk;

/**
 * Who a colonist is: shown as the chat name, with profession and colony on hover.
 *
 * @param job              profession in English, for the prompt
 * @param jobTranslationKey MineColonies translation key of the profession, for players; null if unemployed
 */
public record Speaker(String name, String job, String jobTranslationKey, String colony) {
}
