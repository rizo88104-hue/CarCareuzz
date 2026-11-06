package uz.carcare.admin;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import uz.carcare.admin.AdminDtos.*;

import java.util.List;
import java.util.Map;

/**
 * Admin panel API. Har bir so'rov "Authorization: Bearer TOKEN" sarlavhasi bilan keladi.
 *
 *   /api/admin/login, /logout, /me, /password   — hamma xodimlar
 *   /api/admin/center/dashboard, /lookup,
 *              /records                          — direktor, administrator, usta
 *   /api/admin/center/records/{id}/ready         — direktor, administrator
 *   /api/admin/center/staff, /log                — faqat direktor
 *   /api/admin/super/**                          — super admin
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final String AUTH = HttpHeaders.AUTHORIZATION;

    private final AdminAuthService auth;
    private final AdminService service;

    public AdminController(AdminAuthService auth, AdminService service) {
        this.auth = auth;
        this.service = service;
    }

    // ---------- Kirish ----------

    @PostMapping("/login")
    public AdminAuthService.LoginResult login(@RequestBody LoginRequest req) {
        return auth.login(req.username(), req.password());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(value = AUTH, required = false) String h) {
        if (h != null && h.startsWith("Bearer ")) auth.logout(h.substring(7).trim());
    }

    @GetMapping("/me")
    public MeDto me(@RequestHeader(value = AUTH, required = false) String h) {
        AdminPrincipal p = auth.require(h);
        return new MeDto(p.username(), p.fullName(), p.role().name(), p.role().label(), p.centerName());
    }

    @PostMapping("/password")
    public Map<String, Boolean> password(@RequestHeader(value = AUTH, required = false) String h,
                                         @RequestBody PasswordRequest req) {
        AdminPrincipal p = auth.require(h);
        auth.changePassword(p.staffId(), req.oldPassword(), req.newPassword());
        return Map.of("ok", true);
    }

    // ---------- Servis xodimlari ----------

    @GetMapping("/center/dashboard")
    public CenterDashboard dashboard(@RequestHeader(value = AUTH, required = false) String h) {
        return service.dashboard(auth.requireCenter(h));
    }

    @GetMapping("/center/lookup")
    public CarLookupDto lookup(@RequestHeader(value = AUTH, required = false) String h, @RequestParam String plate) {
        return service.lookup(auth.requireCenter(h), plate);
    }

    @PostMapping("/center/records")
    @ResponseStatus(HttpStatus.CREATED)
    public SaveResult addRecord(@RequestHeader(value = AUTH, required = false) String h,
                                @Valid @RequestBody AdminRecordRequest req) {
        return service.addRecord(auth.requireCenter(h), req);
    }

    @PostMapping("/center/records/{id}/ready")
    public ReadyResult ready(@RequestHeader(value = AUTH, required = false) String h, @PathVariable Long id) {
        return service.sendReady(auth.requireContact(h), id);
    }

    // ---------- Direktor ----------

    @GetMapping("/center/staff")
    public List<StaffDto> staff(@RequestHeader(value = AUTH, required = false) String h) {
        return service.listStaff(auth.requireDirector(h));
    }

    @PostMapping("/center/staff")
    @ResponseStatus(HttpStatus.CREATED)
    public StaffDto addStaff(@RequestHeader(value = AUTH, required = false) String h, @Valid @RequestBody StaffRequest req) {
        return service.addStaff(auth.requireDirector(h), req);
    }

    @PatchMapping("/center/staff/{id}")
    public StaffDto updateStaff(@RequestHeader(value = AUTH, required = false) String h, @PathVariable Long id,
                                @RequestBody StaffUpdateRequest req) {
        return service.updateStaff(auth.requireDirector(h), id, req);
    }

    @GetMapping("/center/log")
    public List<LogDto> centerLog(@RequestHeader(value = AUTH, required = false) String h) {
        return service.centerLog(auth.requireDirector(h));
    }

    // ---------- Super admin ----------

    @GetMapping("/super/overview")
    public SuperOverview overview(@RequestHeader(value = AUTH, required = false) String h) {
        auth.requireSuper(h);
        return service.overview();
    }

    @PostMapping("/super/centers")
    @ResponseStatus(HttpStatus.CREATED)
    public CenterDto createCenter(@RequestHeader(value = AUTH, required = false) String h,
                                  @Valid @RequestBody CenterRequest req) {
        return service.createCenter(auth.requireSuper(h), req);
    }

    @PatchMapping("/super/centers/{id}")
    public CenterDto updateCenter(@RequestHeader(value = AUTH, required = false) String h,
                                  @PathVariable Long id, @RequestBody CenterUpdateRequest req) {
        return service.updateCenter(auth.requireSuper(h), id, req);
    }

    @GetMapping("/super/centers/{id}/missed")
    public List<MissedDto> missed(@RequestHeader(value = AUTH, required = false) String h, @PathVariable Long id) {
        auth.requireSuper(h);
        return service.missed(id);
    }

    @GetMapping("/super/log")
    public List<LogDto> superLog(@RequestHeader(value = AUTH, required = false) String h,
                                 @RequestParam(required = false) Long centerId) {
        auth.requireSuper(h);
        return service.superLog(centerId);
    }
}
