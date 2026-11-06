package uz.carcare.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.carcare.model.AdminSession;
import uz.carcare.model.StaffUser;
import uz.carcare.model.StaffUser.Role;
import uz.carcare.repo.AdminSessionRepository;
import uz.carcare.repo.StaffUserRepository;
import uz.carcare.util.Formats;
import uz.carcare.web.ApiException;

import java.time.Duration;
import java.time.Instant;

/**
 * Admin panelga kirish va huquqlarni tekshirish.
 * Birinchi ishga tushishda super admin yaratiladi: login va parol
 * SUPERADMIN_USERNAME / SUPERADMIN_PASSWORD dan olinadi. Parol berilmasa,
 * tasodifiy parol yaratilib, konsolga bir marta chiqariladi.
 */
@Service
public class AdminAuthService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);
    private static final Duration SESSION_TTL = Duration.ofDays(7);

    private final StaffUserRepository staff;
    private final AdminSessionRepository sessions;
    private final AuditService audit;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final String superUsername;
    private final String superPassword;

    public AdminAuthService(StaffUserRepository staff, AdminSessionRepository sessions, AuditService audit,
                            @Value("${superadmin.username:superadmin}") String superUsername,
                            @Value("${superadmin.password:}") String superPassword) {
        this.staff = staff;
        this.sessions = sessions;
        this.audit = audit;
        this.superUsername = superUsername;
        this.superPassword = superPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Oldingi versiyada servis admini CENTER_ADMIN edi — endi u DIRECTOR
        @SuppressWarnings("deprecation")
        int migrated = staff.migrateRole(Role.CENTER_ADMIN, Role.DIRECTOR);
        if (migrated > 0) log.info("{} ta servis admini DIRECTOR roliga o'tkazildi", migrated);

        if (staff.existsByRole(Role.SUPER_ADMIN)) return;
        String password = superPassword;
        if (password == null || password.isBlank()) {
            password = Formats.newToken().substring(0, 12);
            log.warn("""

                    ==================================================
                     SUPER ADMIN yaratildi
                     Login:  {}
                     Parol:  {}
                     Kirgandan keyin parolni o'zgartirishni unutmang
                     (yoki SUPERADMIN_PASSWORD ni sozlang).
                    ==================================================""", superUsername, password);
        }
        StaffUser u = new StaffUser();
        u.setUsername(superUsername.toLowerCase());
        u.setFullName("Super admin");
        u.setPasswordHash(encoder.encode(password));
        u.setRole(Role.SUPER_ADMIN);
        staff.save(u);
    }

    public String hash(String password) {
        return encoder.encode(password);
    }

    @Transactional
    public LoginResult login(String username, String password) {
        StaffUser u = staff.findByUsername(username == null ? "" : username.trim().toLowerCase()).orElse(null);
        if (u == null || password == null || !encoder.matches(password, u.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Login yoki parol noto'g'ri");
        }
        checkAllowed(u);
        AdminSession s = new AdminSession();
        s.setToken(Formats.newToken() + Formats.newToken());
        s.setStaff(u);
        s.setExpiresAt(Instant.now().plus(SESSION_TTL));
        sessions.save(s);
        AdminPrincipal p = toPrincipal(u);
        audit.staff(p, u.getCenter(), "Tizimga kirdi");
        return new LoginResult(s.getToken(), p);
    }

    @Transactional
    public void logout(String token) {
        if (token != null) sessions.deleteByToken(token);
    }

    /** "Authorization: Bearer ..." sarlavhasidan xodimni aniqlaydi. */
    @Transactional(readOnly = true)
    public AdminPrincipal require(String authHeader) {
        String token = authHeader != null && authHeader.startsWith("Bearer ") ? authHeader.substring(7).trim() : null;
        if (token == null || token.isEmpty()) throw unauthorized();
        AdminSession s = sessions.findWithStaff(token).orElseThrow(AdminAuthService::unauthorized);
        if (s.getExpiresAt().isBefore(Instant.now())) throw unauthorized();
        StaffUser u = s.getStaff();
        checkAllowed(u);
        return toPrincipal(u);
    }

    public AdminPrincipal requireSuper(String authHeader) {
        AdminPrincipal p = require(authHeader);
        if (!p.isSuper()) throw forbidden("Faqat super admin uchun");
        return p;
    }

    /** Istalgan servis xodimi: direktor, administrator, usta. */
    public AdminPrincipal requireCenter(String authHeader) {
        AdminPrincipal p = require(authHeader);
        if (p.centerId() == null) throw forbidden("Faqat avtoservis xodimlari uchun");
        return p;
    }

    /** Direktor yoki administrator: mijozlar bilan aloqa. */
    public AdminPrincipal requireContact(String authHeader) {
        AdminPrincipal p = requireCenter(authHeader);
        if (!p.canContactCustomers()) throw forbidden("Bu amal direktor yoki administrator uchun");
        return p;
    }

    public AdminPrincipal requireDirector(String authHeader) {
        AdminPrincipal p = requireCenter(authHeader);
        if (!p.isDirector()) throw forbidden("Faqat direktor uchun");
        return p;
    }

    @Transactional
    public void changePassword(Long staffId, String oldPassword, String newPassword) {
        StaffUser u = staff.findById(staffId).orElseThrow(AdminAuthService::unauthorized);
        if (oldPassword == null || !encoder.matches(oldPassword, u.getPasswordHash())) {
            throw ApiException.badRequest("Joriy parol noto'g'ri");
        }
        validatePassword(newPassword);
        u.setPasswordHash(encoder.encode(newPassword));
        audit.staff(toPrincipal(u), u.getCenter(), "O'z parolini o'zgartirdi");
    }

    public static void validatePassword(String p) {
        if (p == null || p.length() < 8) throw ApiException.badRequest("Parol kamida 8 belgidan iborat bo'lsin");
    }

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void cleanupExpired() {
        sessions.deleteExpired(Instant.now());
    }

    public AdminPrincipal toPrincipal(StaffUser u) {
        return new AdminPrincipal(u.getId(), u.getUsername(), u.displayName(), u.getRole(),
                u.getCenter() == null ? null : u.getCenter().getId(),
                u.getCenter() == null ? null : u.getCenter().getName());
    }

    private void checkAllowed(StaffUser u) {
        if (u.isBlocked()) throw forbidden("Hisobingiz bloklangan. Direktor bilan bog'laning.");
        if (u.getRole().isCenterRole() && (u.getCenter() == null || !u.getCenter().isActive())) {
            throw forbidden("Bu avtoservis o'chirilgan. Super admin bilan bog'laning.");
        }
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Qaytadan tizimga kiring");
    }

    private static ApiException forbidden(String msg) {
        return new ApiException(HttpStatus.FORBIDDEN, msg);
    }

    public record LoginResult(String token, AdminPrincipal user) {
    }
}
