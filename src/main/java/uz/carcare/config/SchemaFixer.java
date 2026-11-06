package uz.carcare.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Hibernate enum ustunlariga CHECK cheklovi qo'yadi (masalan method in ('CASH','CARD','TRANSFER')).
 * ddl-auto=update yangi enum qiymati qo'shilganda bu cheklovni yangilamaydi, shuning uchun
 * eski bazada "BONUS" kabi yangi qiymat saqlanmay qoladi. Bu klass ishga tushganda eskirgan
 * cheklovlarni olib tashlaydi (qiymatlar baribir Java enum orqali tekshiriladi).
 * PostgreSQL va H2 da ishlaydi; boshqa bazada xato bo'lsa, faqat ogohlantirish yoziladi.
 */
@Component
public class SchemaFixer {

    private static final Logger log = LoggerFactory.getLogger(SchemaFixer.class);

    /** jadval, ustun, eski cheklovda bo'lishi kerak bo'lgan qiymat, yangi qiymat. */
    private static final List<String[]> FIXES = List.of(
            new String[]{"payments", "method", "TRANSFER", "BONUS"},
            new String[]{"staff_users", "role", "SUPER_ADMIN", "DIRECTOR"}
    );

    private final JdbcTemplate jdbc;

    /** EntityManagerFactory — jadvallar (ddl-auto) tayyor bo'lgandan keyin ishlash uchun. */
    public SchemaFixer(JdbcTemplate jdbc, EntityManagerFactory emf) {
        this.jdbc = jdbc;
    }

    /** Ilova ishga tushish skriptlaridan (rol migratsiyasi va h.k.) oldin bajariladi. */
    @PostConstruct
    public void fix() {
        for (String[] f : FIXES) {
            try {
                dropStale(f[0], f[2], f[3]);
            } catch (Exception e) {
                log.warn("{} jadvali cheklovlarini tekshirib bo'lmadi: {}", f[0], e.getMessage());
            }
        }
    }

    private void dropStale(String table, String mustContain, String missing) {
        List<String[]> checks = jdbc.query("""
                        select tc.constraint_name, cc.check_clause
                        from information_schema.table_constraints tc
                        join information_schema.check_constraints cc on cc.constraint_name = tc.constraint_name
                        where lower(tc.table_name) = ? and tc.constraint_type = 'CHECK'
                        """,
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, table);
        for (String[] c : checks) {
            String clause = c[1] == null ? "" : c[1].toUpperCase(Locale.ROOT);
            if (clause.contains("'" + mustContain + "'") && !clause.contains("'" + missing + "'")) {
                jdbc.execute("alter table " + table + " drop constraint \"" + c[0] + "\"");
                log.info("{} jadvalidan eskirgan cheklov olib tashlandi: {}", table, c[0]);
            }
        }
    }
}
