package uz.carcare.model;

/** Sadoqat darajasi. Mijozning shu servisda haqiqiy pul bilan to'lagan jami summasiga qarab beriladi. */
public enum LoyaltyLevel {
    BRONZE("Bronza", "🥉"),
    SILVER("Kumush", "🥈"),
    GOLD("Oltin", "🥇"),
    PLATINUM("Platina", "💎");

    private final String label;
    private final String icon;

    LoyaltyLevel(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    public String label() {
        return label;
    }

    public String icon() {
        return icon;
    }

    public LoyaltyLevel next() {
        return this == PLATINUM ? null : values()[ordinal() + 1];
    }
}
