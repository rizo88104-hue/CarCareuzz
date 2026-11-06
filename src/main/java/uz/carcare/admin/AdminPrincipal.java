package uz.carcare.admin;

import uz.carcare.model.StaffUser.Role;

/** Tizimga kirgan xodim haqida qisqa ma'lumot. */
public record AdminPrincipal(Long staffId, String username, String fullName, Role role, Long centerId, String centerName) {

    public boolean isSuper() {
        return role == Role.SUPER_ADMIN;
    }

    public boolean isDirector() {
        return role == Role.DIRECTOR || role == Role.CENTER_ADMIN;
    }

    public boolean isMaster() {
        return role == Role.MASTER;
    }

    /** Mijozga xabar yuborish, qo'ng'iroq ro'yxati va telefonlarni ko'rish huquqi. */
    public boolean canContactCustomers() {
        return isDirector() || role == Role.ADMINISTRATOR;
    }
}
