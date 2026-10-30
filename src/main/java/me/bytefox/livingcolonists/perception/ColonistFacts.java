package me.bytefox.livingcolonists.perception;

import java.util.List;

/**
 * What a colonist knows about themselves and the colony, collected from MineColonies on the server
 * thread. Plain values only, so it can be rendered into a prompt and tested without a game.
 *
 * @param job             profession in English, null if unemployed
 * @param workplaceMaxLevel highest level the workplace can reach, 0 if unknown
 * @param workNotes       what the job involves right now: orders being made for others, fields, mine levels...
 * @param availableWork   kinds of work the colony has a built workplace for, e.g. "chopping wood (the Forester's Hut)"
 * @param missingWork     kinds of work nobody in the colony can do yet
 * @param homeLevel       0 if the colonist has no home
 * @param saturation      0 to 20, below 8 is hungry
 * @param health          0 to 1, or -1 if the colonist isn't loaded
 * @param sickness        name of the disease, null if healthy
 * @param happiness       0 to 10, or -1 if unknown
 * @param unhappyAbout    happiness factors that drag the colonist down
 * @param requestsUnderway deliveries the workplace waits for that a courier or crafter is already handling
 * @param stuckRequests   things the workplace asked for that nobody has taken on yet
 * @param playerMustBring things the workplace needs that nobody in the colony can provide, so a player has to
 * @param construction    what a builder is building now, null if nothing
 * @param missingMaterials materials the construction still lacks, e.g. "32 Oak Planks"
 * @param materialsOnTheWay kinds of material already being delivered to the builder
 * @param materialLedger  the construction's materials as the resource scroll shows them: used, at hand, on the way
 */
public record ColonistFacts(String job, int workplaceLevel, int workplaceMaxLevel, List<String> workNotes,
                            List<String> availableWork, List<String> missingWork,
                            int homeLevel, double saturation, Food food, double health, String sickness,
                            boolean beingTreated, double happiness, List<String> unhappyAbout, int requestsUnderway,
                            List<String> stuckRequests, List<String> playerMustBring, String construction,
                            List<String> missingMaterials, int materialsOnTheWay, String weather, int colonySize, int unemployed, int homeless,
                            boolean raid, List<Relative> family, List<Relative> coworkers, List<String> materialLedger) {

    /** Where a hungry colonist can get food without the player. */
    public enum Food {
        UNKNOWN,
        NONE,
        POCKETS,
        RESTAURANT
    }

    /**
     * @param relation "wife", "son", "coworker"...
     * @param notes    short states worth knowing: "hungry", "sick", "no home"
     */
    public record Relative(String relation, String name, String job, List<String> notes) {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String job;
        private int workplaceLevel;
        private int workplaceMaxLevel;
        private List<String> workNotes = List.of();
        private List<String> availableWork = List.of();
        private List<String> missingWork = List.of();
        private int homeLevel;
        private double saturation = 20;
        private Food food = Food.UNKNOWN;
        private double health = -1;
        private String sickness;
        private boolean beingTreated;
        private double happiness = -1;
        private List<String> unhappyAbout = List.of();
        private int requestsUnderway;
        private List<String> stuckRequests = List.of();
        private List<String> playerMustBring = List.of();
        private String construction;
        private List<String> missingMaterials = List.of();
        private int materialsOnTheWay;
        private String weather = "clear";
        private int colonySize;
        private int unemployed;
        private int homeless;
        private boolean raid;
        private List<Relative> family = List.of();
        private List<Relative> coworkers = List.of();
        private List<String> materialLedger = List.of();

        public Builder job(String job) { this.job = job; return this; }
        public Builder workplaceLevel(int level) { this.workplaceLevel = level; return this; }
        public Builder workplaceMaxLevel(int level) { this.workplaceMaxLevel = level; return this; }
        public Builder workNotes(List<String> notes) { this.workNotes = List.copyOf(notes); return this; }
        public Builder availableWork(List<String> work) { this.availableWork = List.copyOf(work); return this; }
        public Builder missingWork(List<String> work) { this.missingWork = List.copyOf(work); return this; }
        public Builder homeLevel(int level) { this.homeLevel = level; return this; }
        public Builder saturation(double saturation) { this.saturation = saturation; return this; }
        public Builder food(Food food) { this.food = food; return this; }
        public Builder health(double health) { this.health = health; return this; }
        public Builder sickness(String sickness, boolean beingTreated) {
            this.sickness = sickness;
            this.beingTreated = beingTreated;
            return this;
        }
        public Builder happiness(double happiness) { this.happiness = happiness; return this; }
        public Builder unhappyAbout(List<String> factors) { this.unhappyAbout = List.copyOf(factors); return this; }
        public Builder requests(int underway, List<String> stuck) {
            this.requestsUnderway = underway;
            this.stuckRequests = List.copyOf(stuck);
            return this;
        }
        public Builder playerMustBring(List<String> things) { this.playerMustBring = List.copyOf(things); return this; }
        public Builder construction(String project, List<String> missing, int onTheWay) {
            this.construction = project;
            this.missingMaterials = List.copyOf(missing);
            this.materialsOnTheWay = onTheWay;
            return this;
        }
        public Builder materialLedger(List<String> lines) { this.materialLedger = List.copyOf(lines); return this; }
        public Builder weather(String weather) { this.weather = weather; return this; }
        public Builder colony(int size, int unemployed, int homeless, boolean raid) {
            this.colonySize = size;
            this.unemployed = unemployed;
            this.homeless = homeless;
            this.raid = raid;
            return this;
        }
        public Builder family(List<Relative> family) { this.family = List.copyOf(family); return this; }
        public Builder coworkers(List<Relative> coworkers) { this.coworkers = List.copyOf(coworkers); return this; }

        public ColonistFacts build() {
            return new ColonistFacts(job, workplaceLevel, workplaceMaxLevel, workNotes, availableWork, missingWork, homeLevel, saturation, food, health,
                    sickness, beingTreated, happiness, unhappyAbout, requestsUnderway, stuckRequests, playerMustBring,
                    construction, missingMaterials, materialsOnTheWay, weather, colonySize,
                    unemployed, homeless, raid, family, coworkers, materialLedger);
        }
    }
}
