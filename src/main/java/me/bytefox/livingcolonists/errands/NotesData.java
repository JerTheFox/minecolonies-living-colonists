package me.bytefox.livingcolonists.errands;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/** The messages and reminders colonists keep, saved with the world. */
public final class NotesData extends SavedData {
    public static final String NAME = "livingcolonists_notes";

    final NoteBook book = new NoteBook();

    public static SavedData.Factory<NotesData> factory() {
        return new SavedData.Factory<>(NotesData::new, (tag, provider) -> load(tag), null);
    }

    private static NotesData load(CompoundTag tag) {
        NotesData data = new NotesData();
        ListTag notes = tag.getList("notes", Tag.TAG_COMPOUND);
        for (int i = 0; i < notes.size(); i++) {
            CompoundTag n = notes.getCompound(i);
            NoteBook.Kind kind = "reminder".equals(n.getString("kind")) ? NoteBook.Kind.REMINDER : NoteBook.Kind.MAIL;
            data.book.add(new NoteBook.Note(kind, n.getInt("colony"), n.getInt("citizen"), n.getUUID("from"),
                    n.getString("fromName"), n.getUUID("to"), n.getString("toName"), n.getString("text"),
                    n.getLong("made"), n.getLong("due")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag notes = new ListTag();
        for (NoteBook.Note note : book.all()) {
            CompoundTag n = new CompoundTag();
            n.putString("kind", note.kind() == NoteBook.Kind.REMINDER ? "reminder" : "mail");
            n.putInt("colony", note.colonyId());
            n.putInt("citizen", note.citizenId());
            n.putUUID("from", note.from());
            n.putString("fromName", note.fromName());
            n.putUUID("to", note.to());
            n.putString("toName", note.toName());
            n.putString("text", note.text());
            n.putLong("made", note.made());
            n.putLong("due", note.due());
            notes.add(n);
        }
        tag.put("notes", notes);
        return tag;
    }
}
