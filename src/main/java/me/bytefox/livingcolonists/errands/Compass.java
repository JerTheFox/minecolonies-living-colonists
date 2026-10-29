package me.bytefox.livingcolonists.errands;

/** Which way and how far something is, in words a colonist would say. */
public final class Compass {
    private static final String[] DIRECTIONS = {"south", "south-west", "west", "north-west", "north", "north-east",
            "east", "south-east"};
    /** Closer than this is "right here". */
    private static final int HERE = 6;

    private Compass() {
    }

    /** @param dx east is positive, @param dz south is positive, as in Minecraft */
    public static String way(int dx, int dz) {
        long distance = Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
        if (distance < HERE) {
            return "right here";
        }
        double angle = Math.toDegrees(Math.atan2(-dx, dz));
        int sector = (int) Math.floorMod(Math.round(angle / 45.0), 8L);
        return "about " + distance + " blocks " + DIRECTIONS[sector];
    }
}
