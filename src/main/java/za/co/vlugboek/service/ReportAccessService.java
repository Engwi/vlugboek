package za.co.vlugboek.service;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import za.co.vlugboek.domain.AppUser;
import za.co.vlugboek.domain.DocumentRecord;

@Service
public class ReportAccessService {
    public boolean canRead(DocumentRecord document, Authentication authentication) {
        AppUser user = currentUser(authentication);
        if (user != null && user.getRole().isSystemAdmin()) {
            return true;
        }
        if (user != null && user.getRole().isFederationAdmin()) {
            return inFederationScope(document, user);
        }
        if (!document.isAvailableToUsers()) {
            return false;
        }
        if (user == null) {
            return false;
        }
        return inFederationScope(document, user);
    }

    public boolean canManage(DocumentRecord document, Authentication authentication) {
        AppUser user = currentUser(authentication);
        if (user == null) {
            return false;
        }
        if (user.getRole().isSystemAdmin()) {
            return true;
        }
        return user.getRole().isFederationAdmin() && inFederationScope(document, user);
    }

    public boolean isAdmin(Authentication authentication) {
        AppUser user = currentUser(authentication);
        return user != null && user.getRole().isAdminRole();
    }

    private AppUser currentUser(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AppUser user) {
            return user;
        }
        return null;
    }

    private boolean inFederationScope(DocumentRecord document, AppUser user) {
        return user.getFederation() == null
                || document.getFederation() == null
                || user.getFederation().getId().equals(document.getFederation().getId());
    }
}
