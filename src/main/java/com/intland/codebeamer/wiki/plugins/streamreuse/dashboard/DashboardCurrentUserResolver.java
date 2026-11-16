package com.intland.codebeamer.wiki.plugins.streamreuse.dashboard;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.intland.codebeamer.persistence.dto.UserDto;

/**
 * Resolves the authenticated Codebeamer UserDto without introducing a hard
 * compile dependency on a release-specific authentication helper signature.
 */
@Component
public class DashboardCurrentUserResolver {
    private static final Logger LOG = LogManager.getLogger(DashboardCurrentUserResolver.class);
    private static final List<String> ATTRIBUTE_NAMES = Arrays.asList(
            "user", "currentUser", "loggedInUser", "userDto", "loggedInUserDto");

    public UserDto resolve(HttpServletRequest request) {
        UserDto user = fromKnownAttributes(request);
        if (user != null) return user;

        user = invokeControllerUtils(request);
        if (user != null) return user;

        Principal principal = request.getUserPrincipal();
        if (principal instanceof UserDto) return (UserDto) principal;

        throw new IllegalStateException(
                "The authenticated Codebeamer UserDto could not be resolved. " +
                "Inspect STREAM_REUSE_REQUEST_USER_ATTRIBUTE logs and add the " +
                "environment's user attribute/helper to DashboardCurrentUserResolver.");
    }

    private UserDto fromKnownAttributes(HttpServletRequest request) {
        for (String name : ATTRIBUTE_NAMES) {
            Object value = request.getAttribute(name);
            if (value instanceof UserDto) return (UserDto) value;
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            for (String name : ATTRIBUTE_NAMES) {
                Object value = session.getAttribute(name);
                if (value instanceof UserDto) return (UserDto) value;
            }
        }
        if (LOG.isDebugEnabled()) {
            Enumeration<String> names = request.getAttributeNames();
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                Object value = request.getAttribute(name);
                LOG.debug("STREAM_REUSE_REQUEST_USER_ATTRIBUTE name={}, type={}",
                        name, value == null ? null : value.getClass().getName());
            }
        }
        return null;
    }

    private UserDto invokeControllerUtils(HttpServletRequest request) {
        try {
            Class<?> type = Class.forName("com.intland.codebeamer.controller.ControllerUtils");
            for (String methodName : Arrays.asList("getCurrentUser", "getUser")) {
                try {
                    Method method = type.getMethod(methodName, HttpServletRequest.class);
                    Object value = method.invoke(null, request);
                    if (value instanceof UserDto) return (UserDto) value;
                } catch (NoSuchMethodException ignored) {
                    // Try the next known method name.
                }
            }
        } catch (ReflectiveOperationException exception) {
            LOG.debug("STREAM_REUSE_CONTROLLER_UTILS_USER_LOOKUP_UNAVAILABLE", exception);
        }
        return null;
    }
}
