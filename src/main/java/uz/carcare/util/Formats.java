package uz.carcare.util;

import uz.carcare.web.ApiException;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.regex.Pattern;

/** Telefon, mashina raqami, sana va matn bilan ishlash uchun yordamchi funksiyalar. */
public final class Formats {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    /** O'zbekiston raqamlari: 01A123BC, 01123ABC va shunga o'xshash 6-10 belgili formatlar. */
    private static final Pattern PLATE = Pattern.compile("^[0-9]{2}[A-Z0-9]{4,8}$");

    private Formats() {
    }

    /** "+998 (90) 123-45-67", "901234567" → "998901234567" */
    public static String normalizePhone(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() == 9) digits = "998" + digits;
        if (digits.length() != 12 || !digits.startsWith("998")) {
            throw ApiException.badRequest("Telefon raqam noto'g'ri. Masalan: +998 90 123 45 67");
        }
        return digits;
    }

    /** "01 a 123 bc" → "01A123BC" */
    public static String normalizePlate(String raw) {
        String plate = raw == null ? "" : raw.toUpperCase().replaceAll("[^A-Z0-9]", "");
        if (!PLATE.matcher(plate).matches()) {
            throw ApiException.badRequest("Mashina raqami noto'g'ri. Masalan: 01 A 123 BC");
        }
        return plate;
    }

    /** "998901234567" → "+998 90 123 45 67" */
    public static String prettyPhone(String phone) {
        if (phone == null || phone.length() != 12) return phone;
        return "+" + phone.substring(0, 3) + " " + phone.substring(3, 5) + " " + phone.substring(5, 8)
                + " " + phone.substring(8, 10) + " " + phone.substring(10);
    }

    /** "01A123BC" → "01 A 123 BC" (faqat chiroyli ko'rsatish uchun) */
    public static String prettyPlate(String plate) {
        if (plate == null) return null;
        if (plate.matches("^\\d{2}[A-Z]\\d{3}[A-Z]{2}$")) {
            return plate.substring(0, 2) + " " + plate.charAt(2) + " " + plate.substring(3, 6) + " " + plate.substring(6);
        }
        if (plate.matches("^\\d{2}\\d{3}[A-Z]{3}$")) {
            return plate.substring(0, 2) + " " + plate.substring(2, 5) + " " + plate.substring(5);
        }
        return plate;
    }

    /** 1250000 → "1 250 000 so'm" */
    public static String money(long v) {
        return String.format(java.util.Locale.ROOT, "%,d", v).replace(',', ' ') + " so'm";
    }

    /** 12500 → "12 500 ball" (1 ball = 1 so'm). */
    public static String points(long v) {
        return String.format(java.util.Locale.ROOT, "%,d", v).replace(',', ' ') + " ball";
    }

    /** 40213377 → "4021 3377" */
    public static String prettyCard(String card) {
        return card == null || card.length() != 8 ? card : card.substring(0, 4) + " " + card.substring(4);
    }

    /** Bugun tug'ilgan kunmi. 29-fevralda tug'ilganlar kabisa bo'lmagan yilda 28-fevralda nishonlanadi. */
    public static boolean isBirthday(LocalDate birth, LocalDate today) {
        if (birth == null) return false;
        if (birth.getMonthValue() == 2 && birth.getDayOfMonth() == 29 && !today.isLeapYear()) {
            return today.getMonthValue() == 2 && today.getDayOfMonth() == 28;
        }
        return birth.getMonth() == today.getMonth() && birth.getDayOfMonth() == today.getDayOfMonth();
    }

    public static String date(LocalDate d) {
        return d == null ? "" : d.format(DATE);
    }

    public static String newToken() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); // 24 belgi
    }

    /** Telegram HTML rejimi uchun maxsus belgilarni xavfsiz qilish. */
    public static String html(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
